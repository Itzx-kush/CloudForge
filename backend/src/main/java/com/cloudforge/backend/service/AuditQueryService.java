package com.cloudforge.backend.service;

import com.cloudforge.backend.dto.AuditEventDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.AuditEventEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditQueryService {

    private final AuditEventRepository auditEventRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public AuditQueryService(
            AuditEventRepository auditEventRepository,
            WorkspaceAuthorizationService authorizationService,
            ObjectMapper objectMapper
    ) {
        this.auditEventRepository = auditEventRepository;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResponse<AuditEventDto> listWorkspaceAuditEvents(
            UUID workspaceId,
            String action,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireOwner(workspaceId, user.getId());
        String normalizedAction = StringUtils.hasText(action) ? action.trim().toUpperCase() : null;
        Page<AuditEventEntity> page = auditEventRepository.findByWorkspaceId(workspaceId, normalizedAction, pageable);
        return PagedResponse.from(page, this::toDto);
    }

    private AuditEventDto toDto(AuditEventEntity entity) {
        Map<String, Object> metadata = Map.of();
        if (StringUtils.hasText(entity.getMetadataJson())) {
            try {
                metadata = objectMapper.readValue(entity.getMetadataJson(), new TypeReference<>() {
                });
            } catch (JsonProcessingException ignored) {
                metadata = Map.of();
            }
        }
        return new AuditEventDto(
                entity.getId(),
                entity.getWorkspace() != null ? entity.getWorkspace().getId() : null,
                entity.getActorUsername(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getSummary(),
                metadata,
                entity.getRequestId(),
                entity.getCreatedAt()
        );
    }
}
