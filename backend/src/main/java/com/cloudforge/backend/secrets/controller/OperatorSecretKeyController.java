package com.cloudforge.backend.secrets.controller;

import com.cloudforge.backend.secrets.dto.KeyRotationRunDto;
import com.cloudforge.backend.secrets.dto.KeyUsageSummaryDto;
import com.cloudforge.backend.secrets.dto.TriggerKeyRotationRequest;
import com.cloudforge.backend.secrets.service.SecretKeyRotationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-authenticated internal endpoints for key inventory inspection and
 * DEK-only master key rotation. Never exposed to browser sessions or workers.
 */
@RestController
@RequestMapping("/api/internal/operator/secrets")
public class OperatorSecretKeyController {

    private final SecretKeyRotationService rotationService;

    public OperatorSecretKeyController(SecretKeyRotationService rotationService) {
        this.rotationService = rotationService;
    }

    @GetMapping(value = "/keys/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KeyUsageSummaryDto> getKeyStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(rotationService.getKeyUsageSummary());
    }

    @PostMapping(
            value = "/keys/rotate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<KeyRotationRunDto> rotateMasterKey(
            @Valid @RequestBody(required = false) TriggerKeyRotationRequest request
    ) {
        Integer batchSize = request != null ? request.batchSize() : null;
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        String initiatedBy = request != null ? request.initiatedBy() : null;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(rotationService.rotateMasterKey(batchSize, dryRun, initiatedBy));
    }
}
