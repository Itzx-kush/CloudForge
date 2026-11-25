package com.cloudforge.backend.supplychain;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

@Service
public class ArtifactEvidenceService {
    public record Envelope(@NotBlank @Size(max=8192) String payload, @NotBlank @Size(max=256) String signature) {}
    public record Assessment(int version, UUID workspace, UUID artifact, String image, long assessedAt,
                             Integer critical, Integer high, String sbomSha256, Boolean imageSignatureVerified) {}
    public record Summary(UUID artifact, long assessedAt, int critical, int high, String sbomSha256) {}
    private static final long MAX_AGE = 86400;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OciImageArtifactRepository artifacts;
    private final WorkspaceAuthorizationService access;
    private final AuditService audit;
    private final PublicKey key;
    private final String fingerprint;

    public ArtifactEvidenceService(JdbcTemplate jdbc, ObjectMapper mapper, OciImageArtifactRepository artifacts,
            WorkspaceAuthorizationService access, AuditService audit,
            @Value("${cloudforge.supplychain.public-key:}") String publicKey) throws GeneralSecurityException {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.artifacts = artifacts;
        this.access = access;
        this.audit = audit;
        key = publicKey.isBlank() ? null : KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        fingerprint = key == null ? "" : digest(key.getEncoded());
    }

    @Transactional
    public void submit(UUID workspace, UUID artifactId, UserEntity user, Envelope envelope) {
        access.requireOwner(workspace, user.getId());
        var artifact = artifacts.findByIdAndWorkspaceIdWithDetails(artifactId, workspace)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact unavailable"));
        if (key == null) throw new ConflictException("Evidence verification key is unavailable");
        Assessment report;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(envelope.payload());
            var verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(bytes);
            if (!verifier.verify(Base64.getDecoder().decode(envelope.signature()))) {
                throw new SignatureException();
            }
            report = mapper.readValue(bytes, Assessment.class);
        } catch (Exception e) {
            throw new BadRequestException("Invalid signed assessment");
        }
        long now = Instant.now().getEpochSecond();
        if (report == null || report.version() != 1 || !workspace.equals(report.workspace())
                || !artifactId.equals(report.artifact()) || !artifact.getImmutableReference().equals(report.image())
                || report.assessedAt() > now || report.assessedAt() < now - MAX_AGE
                || report.high() == null || report.high() < 0 || report.critical() == null || report.critical() < 0
                || report.imageSignatureVerified() == null
                || (report.critical() == 0 && report.high() == 0 && !report.imageSignatureVerified())
                || report.sbomSha256() == null || !report.sbomSha256().matches("[a-f0-9]{64}")) {
            throw new BadRequestException("Assessment identity, age or required evidence is invalid");
        }
        lockArtifact(artifact);
        String hash = digest(bytes);
        if (jdbc.queryForObject("select count(*) from artifact_security_evidence where evidence_sha256 = ?", Long.class, hash) > 0) return;
        jdbc.update("""
                insert into artifact_security_evidence
                (id,artifact_id,workspace_id,assessed_at,critical_count,high_count,sbom_sha256,signer_sha256,evidence_sha256)
                values (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), artifactId, workspace, report.assessedAt(), report.critical(), report.high(),
                report.sbomSha256(), fingerprint, hash);
        audit.record(artifact.getWorkspace(), user, "SECURITY_EVIDENCE_RECORDED", "OCI_IMAGE_ARTIFACT",
                artifactId.toString(), "Recorded signed vulnerability assessment", Map.of());
    }

    public void requireProductionApproval(OciImageArtifactEntity artifact) {
        lockArtifact(artifact);
        var rows = jdbc.query("""
                select critical_count, high_count, assessed_at, signer_sha256 from artifact_security_evidence
                where artifact_id=? and workspace_id=? order by assessed_at desc limit 1
                """, (rs, i) -> key != null && fingerprint.equals(rs.getString(4))
                        && rs.getLong(3) >= Instant.now().getEpochSecond() - MAX_AGE
                        && rs.getInt(1) == 0 && rs.getInt(2) == 0, artifact.getId(), artifact.getWorkspace().getId());
        if (!rows.contains(true)) throw new ConflictException("Production requires current signed evidence with zero high or critical findings");
    }

    public List<Summary> recent(UUID workspace, UserEntity user) {
        access.requireMembership(workspace, user.getId());
        return jdbc.query("""
                select artifact_id, assessed_at, critical_count, high_count, sbom_sha256 from artifact_security_evidence
                where workspace_id=? order by assessed_at desc, id desc limit 50
                """, (rs, i) -> new Summary(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3),
                rs.getInt(4), rs.getString(5)), workspace);
    }

    private void lockArtifact(OciImageArtifactEntity artifact) {
        jdbc.queryForObject("select id from oci_image_artifacts where id=? and workspace_id=? for update",
                UUID.class, artifact.getId(), artifact.getWorkspace().getId());
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
