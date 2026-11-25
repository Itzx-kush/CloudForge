package com.cloudforge.backend.supplychain;

import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/security")
public class ArtifactEvidenceController {
    private final ArtifactEvidenceService evidence;
    private final CurrentUserResolver users;

    public ArtifactEvidenceController(ArtifactEvidenceService evidence, CurrentUserResolver users) {
        this.evidence = evidence;
        this.users = users;
    }

    @GetMapping("/assessments")
    public List<ArtifactEvidenceService.Summary> recent(@PathVariable UUID workspaceId, Authentication auth) {
        return evidence.recent(workspaceId, users.requireUserEntity(auth));
    }

    @PostMapping("/artifacts/{artifactId}/assessments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@PathVariable UUID workspaceId, @PathVariable UUID artifactId,
            @Valid @RequestBody ArtifactEvidenceService.Envelope envelope, Authentication auth) {
        evidence.submit(workspaceId, artifactId, users.requireUserEntity(auth), envelope);
    }
}
