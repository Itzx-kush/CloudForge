package com.cloudforge.worker.source;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class GitHubSourceArchive {

    private static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 12_000;
    private static final int ZIP_END_HEADER_BYTES = 22;
    private static final int ZIP_DIRECTORY_HEADER_BYTES = 46;
    private static final int IO_TIMEOUT_MILLIS = 5_000;
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern UNSAFE_PATH = Pattern.compile("[\\\\:\\x00-\\x1f\\x7f]");
    private static final Pattern DOS_DEVICE = Pattern.compile(
            "(?i)(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private static final int UNIX_FILE_TYPE = 0170000;
    private static final int UNIX_REGULAR = 0100000;
    private static final int UNIX_DIRECTORY = 0040000;

    private GitHubSourceArchive() {
    }

    public static Inspection acquire(URI downloadUrl, String repository, String sha, Instant deadline,
                                     AtomicBoolean cancelled) throws IOException {
        validateLocation(downloadUrl, repository, sha);
        checkActive(deadline, cancelled);
        Path temporary = Files.createTempDirectory("cloudforge-source-");
        try {
            Path archive = temporary.resolve("source.zip");
            Path source = Files.createDirectory(temporary.resolve("source"));
            download(downloadUrl, archive, deadline, cancelled);
            return extract(archive, source, MAX_SOURCE_BYTES, MAX_ENTRIES, deadline, cancelled);
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path entry : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(entry);
                }
            }
        }
    }

    static void validateLocation(URI location, String repository, String sha) throws IOException {
        if (location == null || repository == null || sha == null || !REPO.matcher(repository).matches()
                || !SHA.matcher(sha).matches() || !"https".equals(location.getScheme())
                || !"codeload.github.com".equalsIgnoreCase(location.getHost())
                || location.getPort() != -1 || location.getRawUserInfo() != null
                || location.getRawFragment() != null || location.toASCIIString().length() > 4096) {
            throw new IOException("Invalid GitHub archive location");
        }
        String path = "/" + repository + "/";
        if (!(location.getRawPath().equalsIgnoreCase(path + "legacy.zip/" + sha)
                || location.getRawPath().equalsIgnoreCase(path + "zip/" + sha))) {
            throw new IOException("GitHub archive location does not match the leased source");
        }
    }

    private static void download(URI url, Path archive, Instant deadline, AtomicBoolean cancelled) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(IO_TIMEOUT_MILLIS);
        connection.setReadTimeout(IO_TIMEOUT_MILLIS);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_ARCHIVE_BYTES) {
                throw new IOException("GitHub archive download was rejected or exceeded the size limit");
            }
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(archive)) {
                copy(input, output, MAX_ARCHIVE_BYTES, null, deadline, cancelled);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Inspection extract(Path archive, Path source, long maxBytes, int maxEntries,
                              Instant deadline, AtomicBoolean cancelled) throws IOException {
        boundDirectory(archive, maxEntries, deadline, cancelled);
        long extractedBytes = 0;
        int entryCount = 0;
        int fileCount = 0;
        String root = null;
        Set<Path> recorded = new HashSet<>();
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                checkActive(deadline, cancelled);
                ZipArchiveEntry entry = entries.nextElement();
                if (++entryCount > maxEntries || !zip.canReadEntryData(entry) || !isSafeType(entry)) {
                    throw new IOException("GitHub archive exceeds entry limits or contains unsupported file types");
                }

                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                String[] parts = name.split("/", -1);
                if (parts.length > 64 || name.length() > 1024) {
                    throw new IOException("GitHub archive contains an oversized path");
                }
                for (String part : parts) {
                    if (part.isEmpty() || part.length() > 255 || part.equals(".") || part.equals("..")
                            || part.endsWith(".") || part.endsWith(" ") || part.equalsIgnoreCase(".git")
                            || UNSAFE_PATH.matcher(part).find() || DOS_DEVICE.matcher(part).matches()) {
                        throw new IOException("GitHub archive contains an unsafe path");
                    }
                }
                if (root == null) {
                    root = parts[0];
                }
                if (!root.equals(parts[0]) || (parts.length == 1 && !entry.isDirectory())) {
                    throw new IOException("GitHub archive must have a single source root");
                }
                Path target = source;
                for (int index = 1; index < parts.length; index++) {
                    target = target.resolve(parts[index]);
                }
                if (!target.normalize().startsWith(source) || !recorded.add(target)) {
                    throw new IOException("GitHub archive contains an invalid or duplicate path");
                }
                if (entry.isDirectory()) {
                    if (entry.getSize() != 0) {
                        throw new IOException("GitHub archive contains invalid directory data");
                    }
                    Files.createDirectories(target);
                    continue;
                }
                if (entry.getSize() < 0 || entry.getSize() > maxBytes - extractedBytes || entry.getCrc() < 0) {
                    throw new IOException("GitHub archive exceeds the extracted size limit");
                }
                Files.createDirectories(target.getParent());
                CRC32 checksum = new CRC32();
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    long bytes = copy(input, output, entry.getSize(), checksum, deadline, cancelled);
                    if (bytes != entry.getSize() || checksum.getValue() != entry.getCrc()) {
                        throw new IOException("GitHub archive entry failed integrity validation");
                    }
                    extractedBytes += bytes;
                }
                fileCount++;
            }
        }
        if (fileCount == 0) {
            throw new IOException("GitHub archive contains no source files");
        }
        checkActive(deadline, cancelled);
        return new Inspection(fileCount, extractedBytes);
    }

    private static void boundDirectory(Path archive, int maxEntries, Instant deadline,
                                       AtomicBoolean cancelled) throws IOException {
        // Check the actual central-directory entry count before the ZIP reader builds its index.
        checkActive(deadline, cancelled);
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            int tailSize = (int) Math.min(file.length(), ZIP_END_HEADER_BYTES + 65_535L);
            byte[] tail = new byte[tailSize];
            long tailOffset = file.length() - tailSize;
            file.seek(tailOffset);
            file.readFully(tail);
            ByteBuffer end = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = tailSize - ZIP_END_HEADER_BYTES; index >= 0; index--) {
                if (end.getInt(index) != 0x06054b50
                        || index + ZIP_END_HEADER_BYTES + unsignedShort(end, index + 20) != tailSize) {
                    continue;
                }
                int count = unsignedShort(end, index + 10);
                long directorySize = Integer.toUnsignedLong(end.getInt(index + 12));
                long offset = Integer.toUnsignedLong(end.getInt(index + 16));
                long directoryEnd = tailOffset + index;
                if (count > maxEntries || count == 65_535 || unsignedShort(end, index + 8) != count
                        || end.getInt(index + 4) != 0 || offset + directorySize != directoryEnd) {
                    break;
                }
                file.seek(offset);
                byte[] header = new byte[ZIP_DIRECTORY_HEADER_BYTES];
                ByteBuffer entry = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                for (int entryNumber = 0; entryNumber < count; entryNumber++) {
                    checkActive(deadline, cancelled);
                    file.readFully(header);
                    long next = file.getFilePointer() + unsignedShort(entry, 28)
                            + unsignedShort(entry, 30) + unsignedShort(entry, 32);
                    if (entry.getInt(0) != 0x02014b50 || unsignedShort(entry, 34) != 0 || next > directoryEnd) {
                        throw new IOException("GitHub archive contains an invalid ZIP directory");
                    }
                    file.seek(next);
                }
                if (file.getFilePointer() == directoryEnd) {
                    return;
                }
                break;
            }
            throw new IOException("GitHub archive exceeds entry limits or has an unsupported ZIP directory");
        }
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long copy(InputStream input, OutputStream output, long limit, CRC32 checksum,
                             Instant deadline, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0;
        while (true) {
            checkActive(deadline, cancelled);
            int count = input.read(buffer);
            if (count == -1) {
                return copied;
            }
            if (count > limit - copied) {
                throw new IOException("GitHub archive stream exceeds the allowed byte limit");
            }
            output.write(buffer, 0, count);
            if (checksum != null) {
                checksum.update(buffer, 0, count);
            }
            copied += count;
        }
    }

    private static boolean isSafeType(ZipArchiveEntry entry) {
        int mode = entry.getUnixMode();
        int type = mode & UNIX_FILE_TYPE;
        return (mode & 07000) == 0 && !entry.isUnixSymlink()
                && (type == 0 || (entry.isDirectory() ? type == UNIX_DIRECTORY : type == UNIX_REGULAR));
    }

    private static void checkActive(Instant deadline, AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) {
            throw new CancellationException();
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new SourceTimeoutException();
        }
    }

    public record Inspection(int fileCount, long extractedBytes) {
    }

    public static final class SourceTimeoutException extends IOException {
    }
}
