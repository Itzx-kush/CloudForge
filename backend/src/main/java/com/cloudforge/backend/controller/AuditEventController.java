package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.AuditEventDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.service.AuditQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/audit-events")
public class AuditEventController {

    private final AuditQueryService auditQueryService;
    private final CurrentUserResolver currentUserResolver;

    public AuditEventController(AuditQueryService auditQueryService, CurrentUserResolver currentUserResolver) {
        this.auditQueryService = auditQueryService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<AuditEventDto>> listAuditEvents(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return ResponseEntity.ok(auditQueryService.listWorkspaceAuditEvents(workspaceId, action, pageable, user));
    }
}
