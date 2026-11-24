package com.cloudforge.worker.source;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubSourceArchiveTest {

    @TempDir
    Path temporary;
    private static final Instant DEADLINE = Instant.MAX;
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void readsRealZipContentAndStripsTheSourceRoot() throws IOException {
        Path zip = archive("acme-source-src/app/hello.txt", 0100644);
        Path source = Files.createDirectory(temporary.resolve("source"));

        var result = GitHubSourceArchive.extract(zip, source, 64, 5, DEADLINE, new AtomicBoolean());

        assertThat(result.fileCount()).isEqualTo(1);
        assertThat(result.extractedBytes()).isEqualTo(5);
        assertThat(source.resolve("app/hello.txt")).hasContent("hello");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/../outside.txt", "/src/outside.txt", "src/.git/config", "src/C:/file.txt"})
    void rejectsUnsafePaths(String path) throws IOException {
        Path zip = archive(path, 0100644);
        Path source = Files.createDirectory(temporary.resolve("source"));

        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 64, 5, DEADLINE, new AtomicBoolean()))
                .isInstanceOf(IOException.class).hasMessageContaining("unsafe path");
        assertThat(temporary.resolve("outside.txt")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(ints = {0120777, 0020600, 0104755})
    void rejectsUnixLinksSpecialFilesAndElevatedModes(int mode) throws IOException {
        Path zip = archive("src/link", mode);
        Path source = Files.createDirectory(temporary.resolve("source"));

        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 64, 5, DEADLINE, new AtomicBoolean()))
                .isInstanceOf(IOException.class).hasMessageContaining("unsupported file types");
    }

    @Test
    void stopsWhenPayloadOrEntryBudgetsAreExceeded() throws IOException {
        Path zip = archive("src/hello.txt", 0100644);
        Path source = Files.createDirectory(temporary.resolve("source"));
        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 4, 5, DEADLINE, new AtomicBoolean()))
                .isInstanceOf(IOException.class).hasMessageContaining("extracted size limit");
        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 64, 0, DEADLINE, new AtomicBoolean()))
                .isInstanceOf(IOException.class).hasMessageContaining("entry limits");
    }

    @Test
    void stopsOnCancellationOrDeadline() throws IOException {
        Path zip = archive("src/hello.txt", 0100644);
        Path source = Files.createDirectory(temporary.resolve("source"));
        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 64, 5, DEADLINE, new AtomicBoolean(true)))
                .isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> GitHubSourceArchive.extract(zip, source, 64, 5, Instant.EPOCH, new AtomicBoolean()))
                .isInstanceOf(GitHubSourceArchive.SourceTimeoutException.class);
    }

    @Test
    void acceptsOnlyGitHubCodeloadForTheLeasedRepositoryAndCommit() throws IOException {
        URI approved = URI.create("https://codeload.github.com/acme/source/legacy.zip/" + SHA + "?token=fixture");
        GitHubSourceArchive.validateLocation(approved, "acme/source", SHA);
        assertThatThrownBy(() -> GitHubSourceArchive.validateLocation(approved, "acme/other", SHA))
                .isInstanceOf(IOException.class).hasMessageNotContaining("fixture");
        URI redirected = URI.create("https://example.invalid/acme/source/legacy.zip/" + SHA);
        assertThatThrownBy(() -> GitHubSourceArchive.validateLocation(redirected, "acme/source", SHA))
                .isInstanceOf(IOException.class);
    }

    private Path archive(String path, int mode) throws IOException {
        Path zipFile = temporary.resolve("input.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(zipFile)) {
            ZipArchiveEntry entry = new ZipArchiveEntry(path);
            entry.setUnixMode(mode);
            zip.putArchiveEntry(entry);
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        return zipFile;
    }
}
