package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.WebhookAckResponse;
import com.cloudforge.backend.service.GitHubWebhookService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(value = "/github", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookAckResponse> receiveGitHubWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature256,
            @RequestHeader(name = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId
    ) {
        GitHubWebhookService.WebhookProcessingResult result =
                webhookService.processWebhook(rawBody, signature256, eventType, deliveryId);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }
}
