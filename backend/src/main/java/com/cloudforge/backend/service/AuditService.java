package com.cloudforge.backend.service;

import com.cloudforge.backend.config.RequestCorrelationFilter;
import com.cloudforge.backend.entity.AuditEventEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Set<String> REDACTED_KEYWORDS = Set.of(
            "secret", "token", "password", "private_key", "privatekey", "authorization", "signature", "cookie"
    );

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AuditEventEntity record(
            WorkspaceEntity workspace,
            UserEntity actor,
            String action,
            String resourceType,
            String resourceId,
            String summary,
            Map<String, Object> metadata
    ) {
        AuditEventEntity event = new AuditEventEntity();
        event.setWorkspace(workspace);
        event.setActor(actor);
        event.setActorUsername(actor != null ? actor.getUsername() : "system");
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setSummary(truncate(summary, 500));
        event.setMetadataJson(serializeSafely(metadata));
        event.setRequestId(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID_KEY));

        AuditEventEntity saved = auditEventRepository.save(event);
        log.info(
                "Audit event action={} resourceType={} resourceId={} workspaceId={} actor={}",
                action,
                resourceType,
                resourceId,
                workspace != null ? workspace.getId() : "none",
                event.getActorUsername()
        );
        return saved;
    }

    private String serializeSafely(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            boolean sensitive = REDACTED_KEYWORDS.stream().anyMatch(lower::contains);
            sanitized.put(key, sensitive ? "[REDACTED]" : value);
        });
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize audit metadata: {}", ex.getMessage());
            return "{}";
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
