package com.cloudforge.worker.oci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and cryptographically verifies an OCI Image Layout TAR archive produced by BuildKit
 * ({@code --output type=oci,dest=...}).
 *
 * <p>Verifies {@code oci-layout}, {@code index.json}, every referenced manifest/config/layer
 * blob's exact SHA-256 digest and byte size, and produces an ordered upload plan for the
 * OCI Distribution v1.1 registry client.</p>
 */
public final class OciLayoutInspector {

    public static final int MAX_TAR_ENTRIES = 2048;
    public static final long MAX_LAYOUT_BYTES = 256L * 1024 * 1024;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OciLayoutInspector() {
    }

    public static VerifiedOciLayout inspectAndVerify(Path ociTarPath) throws IOException {
        if (ociTarPath == null || !Files.isRegularFile(ociTarPath)) {
            throw new IOException("OCI image layout archive does not exist");
        }
        long archiveSize = Files.size(ociTarPath);
        if (archiveSize == 0 || archiveSize > MAX_LAYOUT_BYTES) {
            throw new IOException("OCI image layout archive size (" + archiveSize + ") exceeds bounds");
        }

        Map<String, byte[]> entries = readBoundedTar(ociTarPath);

        byte[] layoutBytes = entries.get("oci-layout");
        if (layoutBytes == null) {
            throw new IOException("OCI layout archive missing required oci-layout descriptor");
        }
        JsonNode layoutJson = MAPPER.readTree(layoutBytes);
        if (!"1.0.0".equals(layoutJson.path("imageLayoutVersion").asText())) {
            throw new IOException("Unsupported OCI imageLayoutVersion");
        }

        byte[] indexBytes = entries.get("index.json");
        if (indexBytes == null) {
            throw new IOException("OCI layout archive missing required index.json");
        }
        JsonNode indexJson = MAPPER.readTree(indexBytes);
        JsonNode manifests = indexJson.path("manifests");
        if (!manifests.isArray() || manifests.isEmpty()) {
            throw new IOException("OCI index.json contains no manifest descriptors");
        }

        JsonNode topDesc = manifests.get(0);
        String topDigest = requireDigest(topDesc.path("digest").asText());
        String topMediaType = topDesc.path("mediaType").asText("application/vnd.oci.image.manifest.v1+json");
        long topDeclaredSize = topDesc.path("size").asLong(-1);

        byte[] topManifestBytes = requireVerifiedBlob(entries, topDigest, topDeclaredSize);
        JsonNode topManifestJson = MAPPER.readTree(topManifestBytes);

        // If BuildKit emitted an OCI Image Index wrapping a single image manifest, unwrap to the
        // target platform's image manifest (or push the manifest directly) so registry digest
        // verification is deterministic across all OCI Distribution v1.1 registries.
        String effectiveDigest = topDigest;
        String effectiveMediaType = topMediaType;
        byte[] effectiveManifestBytes = topManifestBytes;
        JsonNode effectiveManifestJson = topManifestJson;
        String platform = extractPlatform(topDesc);

        if (isImageIndexMediaType(topMediaType) && topManifestJson.path("manifests").isArray()
                && !topManifestJson.path("manifests").isEmpty()) {
            JsonNode selectedChild = selectPrimaryManifest(topManifestJson.path("manifests"));
            String childDigest = requireDigest(selectedChild.path("digest").asText());
            String childMediaType = selectedChild.path("mediaType").asText("application/vnd.oci.image.manifest.v1+json");
            long childSize = selectedChild.path("size").asLong(-1);
            byte[] childBytes = requireVerifiedBlob(entries, childDigest, childSize);
            JsonNode childJson = MAPPER.readTree(childBytes);
            effectiveDigest = childDigest;
            effectiveMediaType = childMediaType;
            effectiveManifestBytes = childBytes;
            effectiveManifestJson = childJson;
            String childPlatform = extractPlatform(selectedChild);
            if (childPlatform != null) {
                platform = childPlatform;
            }
        }

        // Collect & verify config blob + all layer blobs referenced by the image manifest
        Set<String> seenDigests = new LinkedHashSet<>();
        List<BlobEntry> blobs = new ArrayList<>();
        long totalImageBytes = effectiveManifestBytes.length;

        JsonNode configNode = effectiveManifestJson.path("config");
        if (configNode.isObject() && configNode.has("digest")) {
            String cfgDigest = requireDigest(configNode.path("digest").asText());
            long cfgSize = configNode.path("size").asLong(-1);
            String cfgMediaType = configNode.path("mediaType").asText("application/vnd.oci.image.config.v1+json");
            byte[] cfgBytes = requireVerifiedBlob(entries, cfgDigest, cfgSize);
            if (platform == null) {
                JsonNode cfgJson = MAPPER.readTree(cfgBytes);
                String os = cfgJson.path("os").asText("");
                String arch = cfgJson.path("architecture").asText("");
                if (!os.isBlank() && !arch.isBlank()) {
                    platform = os + "/" + arch;
                }
            }
            if (seenDigests.add(cfgDigest)) {
                blobs.add(new BlobEntry(cfgDigest, cfgMediaType, cfgBytes));
                totalImageBytes += cfgBytes.length;
            }
        }

        JsonNode layersNode = effectiveManifestJson.path("layers");
        if (layersNode.isArray()) {
            for (JsonNode layer : layersNode) {
                String layerDigest = requireDigest(layer.path("digest").asText());
                long layerSize = layer.path("size").asLong(-1);
                String layerMediaType = layer.path("mediaType").asText("application/vnd.oci.image.layer.v1.tar+gzip");
                byte[] layerBytes = requireVerifiedBlob(entries, layerDigest, layerSize);
                if (seenDigests.add(layerDigest)) {
                    blobs.add(new BlobEntry(layerDigest, layerMediaType, layerBytes));
                    totalImageBytes += layerBytes.length;
                }
            }
        }

        if (platform == null || platform.isBlank()) {
            platform = "linux/amd64";
        }

        return new VerifiedOciLayout(
                effectiveDigest,
                effectiveMediaType,
                platform,
                totalImageBytes,
                effectiveManifestBytes,
                List.copyOf(blobs)
        );
    }

    public static String sha256FileHex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String sha256Digest(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Map<String, byte[]> readBoundedTar(Path tarPath) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int count = 0;
        long totalRead = 0L;
        try (InputStream fis = Files.newInputStream(tarPath);
             TarArchiveInputStream tis = new TarArchiveInputStream(fis)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                if (++count > MAX_TAR_ENTRIES) {
                    throw new IOException("OCI layout TAR exceeds maximum entry count");
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new IOException("Symbolic links are prohibited inside OCI layout archive");
                }
                String name = normalizeTarPath(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 0 || size > MAX_LAYOUT_BYTES - totalRead) {
                    throw new IOException("OCI layout TAR entry exceeds allowed byte bounds");
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(size, 65536));
                byte[] buf = new byte[8192];
                int r;
                long entryRead = 0;
                while ((r = tis.read(buf)) != -1) {
                    if (r > MAX_LAYOUT_BYTES - totalRead - entryRead) {
                        throw new IOException("OCI layout TAR stream exceeds allowed byte bounds");
                    }
                    baos.write(buf, 0, r);
                    entryRead += r;
                }
                totalRead += entryRead;
                entries.put(name, baos.toByteArray());
            }
        }
        return entries;
    }

    private static String normalizeTarPath(String rawName) throws IOException {
        String name = rawName == null ? "" : rawName.replace("\\", "/");
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/") || name.contains("..")) {
            throw new IOException("Unsafe path inside OCI layout TAR: " + rawName);
        }
        return name;
    }

    private static byte[] requireVerifiedBlob(Map<String, byte[]> entries, String digest, long expectedSize)
            throws IOException {
        String hex = digest.substring("sha256:".length());
        byte[] bytes = entries.get("blobs/sha256/" + hex);
        if (bytes == null) {
            throw new IOException("Missing OCI layout blob for digest " + digest);
        }
        if (expectedSize >= 0 && bytes.length != expectedSize) {
            throw new IOException("OCI blob size mismatch for " + digest + " (expected " + expectedSize + ", got " + bytes.length + ")");
        }
        String computed = sha256Digest(bytes);
        if (!computed.equals(digest)) {
            throw new IOException("OCI blob SHA-256 verification failed: expected " + digest + " but computed " + computed);
        }
        return bytes;
    }

    private static JsonNode selectPrimaryManifest(JsonNode manifestsArray) {
        for (JsonNode desc : manifestsArray) {
            String ann = desc.path("annotations").path("vnd.docker.reference.type").asText("");
            if (!"attestation-manifest".equalsIgnoreCase(ann)) {
                return desc;
            }
        }
        return manifestsArray.get(0);
    }

    private static String extractPlatform(JsonNode desc) {
        JsonNode p = desc.path("platform");
        if (!p.isObject()) {
            return null;
        }
        String os = p.path("os").asText("");
        String arch = p.path("architecture").asText("");
        if (!os.isBlank() && !arch.isBlank() && !"unknown".equalsIgnoreCase(os)) {
            return os + "/" + arch;
        }
        return null;
    }

    private static boolean isImageIndexMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String lower = mediaType.toLowerCase(Locale.ROOT);
        return lower.contains("image.index") || lower.contains("manifest.list");
    }

    private static String requireDigest(String raw) throws IOException {
        if (raw == null) {
            throw new IOException("Missing OCI digest");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(normalized).matches()) {
            throw new IOException("Invalid OCI digest format: " + raw);
        }
        return normalized;
    }

    public record BlobEntry(String digest, String mediaType, byte[] bytes) {
    }

    public record VerifiedOciLayout(
            String manifestDigest,
            String mediaType,
            String platform,
            long totalImageBytes,
            byte[] manifestBytes,
            List<BlobEntry> blobs
    ) {
    }
}
