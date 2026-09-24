package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.dto.request.CreateWebhookRequest;
import com.projects.webhookdeliveryservice.dto.request.UpdateWebhookRequest;
import com.projects.webhookdeliveryservice.dto.response.WebhookResponse;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.service.WebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    public ResponseEntity<WebhookResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRequest request,
            @AuthenticationPrincipal User user){
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookService.createWebhook(request, user));
    }

    @GetMapping
    public ResponseEntity<List<WebhookResponse>> getWebhooks(
            @AuthenticationPrincipal User user){
        return ResponseEntity.status(HttpStatus.OK).body(webhookService.getMyWebhooks(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WebhookResponse> getWebhook(
            @PathVariable Long id,
            @AuthenticationPrincipal User user){
        return ResponseEntity.status(HttpStatus.OK).body(webhookService.getWebhookDetails(id, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WebhookResponse> updateWebhook(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateWebhookRequest request){
        return ResponseEntity.status(HttpStatus.OK).body(webhookService.updateWebhook(id, request, user));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWebhook(
            @PathVariable Long id,
            @AuthenticationPrincipal User user){
        webhookService.deleteWebhook(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/regenerate-secret")
    public ResponseEntity<WebhookResponse> regenerateSecret(
            @PathVariable Long id,
            @AuthenticationPrincipal User user){
        return ResponseEntity.status(HttpStatus.OK).body(webhookService.regenerateSecret(id,user));
    }

}
