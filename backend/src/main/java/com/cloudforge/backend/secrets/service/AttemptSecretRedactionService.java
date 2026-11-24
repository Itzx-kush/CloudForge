package com.cloudforge.backend.secrets.service;

import com.cloudforge.backend.secrets.crypto.SecretCryptoException;
import com.cloudforge.backend.secrets.crypto.SecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretManagementUnavailableException;
import com.cloudforge.backend.secrets.entity.PipelineRunSecretReferenceEntity;
import com.cloudforge.backend.secrets.repository.PipelineRunSecretReferenceRepository;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves bound secret plaintext values transiently in memory exclusively for
 * control-plane redaction during log ingestion and attempt completion sanitization.
 * Fails open to already-known pattern redaction if a KEK is unavailable so log
 * sanitization and cancellation reporting never crash or drop records.
 */
@Service
public class AttemptSecretRedactionService {

    private final SecretKeyProvider keyProvider;
    private final SecretManagementService secretManagementService;
    private final PipelineRunSecretReferenceRepository secretReferenceRepository;

    public AttemptSecretRedactionService(
            SecretKeyProvider keyProvider,
            SecretManagementService secretManagementService,
            PipelineRunSecretReferenceRepository secretReferenceRepository
    ) {
        this.keyProvider = keyProvider;
        this.secretManagementService = secretManagementService;
        this.secretReferenceRepository = secretReferenceRepository;
    }

    @Transactional(readOnly = true)
    public List<String> resolveSecretsForAttemptRedaction(StageExecutionAttemptEntity attempt) {
        if (attempt == null || attempt.getStageRun() == null) {
            return List.of();
        }
        UUID stageRunId = attempt.getStageRun().getId();
        return resolveSecretsForStageRedaction(stageRunId, attempt.getStageRun().isHasSecretBindings());
    }

    @Transactional(readOnly = true)
    public List<String> resolveSecretsForStageRedaction(UUID stageRunId, boolean hasBindingsHint) {
        if (stageRunId == null || (!hasBindingsHint && !secretReferenceRepository.existsByStageRunId(stageRunId))) {
            return List.of();
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            return List.of();
        }
        List<PipelineRunSecretReferenceEntity> refs =
                secretReferenceRepository.findAllByStageRunIdWithSecretDetails(stageRunId);
        if (refs.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(refs.size());
        for (PipelineRunSecretReferenceEntity ref : refs) {
            try {
                values.add(secretManagementService.decryptVersionValue(ref.getSecret(), ref.getSecretVersion()));
            } catch (SecretCryptoException | SecretManagementUnavailableException ignored) {
                // Skip undecryptable entries cleanly; pattern-based redaction remains active.
            }
        }
        return values;
    }
}
