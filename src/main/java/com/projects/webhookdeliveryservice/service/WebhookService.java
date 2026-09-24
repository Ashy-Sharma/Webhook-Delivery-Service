package com.projects.webhookdeliveryservice.service;

import tools.jackson.core.JacksonException;
import com.projects.webhookdeliveryservice.dto.request.CreateWebhookRequest;
import com.projects.webhookdeliveryservice.dto.request.UpdateWebhookRequest;
import com.projects.webhookdeliveryservice.dto.response.WebhookResponse;
import com.projects.webhookdeliveryservice.entity.User;
import com.projects.webhookdeliveryservice.entity.Webhook;
import com.projects.webhookdeliveryservice.exception.ResourceNotFoundException;
import com.projects.webhookdeliveryservice.repository.WebhookRepository;
import com.projects.webhookdeliveryservice.util.SecretKeyGeneratorUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final ObjectMapper objectMapper;

    private List<String> deserializeEventTypes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            throw new RuntimeException("Corrupt event_types JSON", e);
        }
    }

    private String serializeEventTypes(List<String> eventTypes) {
        try {
            return objectMapper.writeValueAsString(eventTypes);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize event types", e);
        }
    }

    private Webhook getOwnedWebhookOrThrow(Long id, User user) {
        return webhookRepository.findByIdAndOwner(id, user)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook not found"));
    }

    private WebhookResponse mapToResponse(Webhook webhook, boolean includeSecret) {
        return WebhookResponse.builder()
                .id(webhook.getId())
                .secretKey(includeSecret ? webhook.getSecretKey() : null)
                .description(webhook.getDescription())
                .isActive(webhook.isActive())
                .createdAt(webhook.getCreatedAt())
                .url(webhook.getUrl())
                .eventType(deserializeEventTypes(webhook.getEventType()))
                .build();
    }

    @Transactional
    public WebhookResponse createWebhook(CreateWebhookRequest request, User user){

        Webhook webhook = Webhook.builder()
                .url(request.getUrl())
                .description(request.getDescription())
                .eventType(serializeEventTypes(request.getEventType()))
                .secretKey(SecretKeyGeneratorUtil.generateSecret())
                .owner(user)
                .build();

        Webhook savedWebhook = webhookRepository.save(webhook);
        return mapToResponse(savedWebhook, true);
    }

    public WebhookResponse getWebhookDetails(Long id, User user){
        return mapToResponse(getOwnedWebhookOrThrow(id, user), false);
    }

    public List<WebhookResponse> getMyWebhooks(User user){
        List<Webhook> webhooks = webhookRepository.findAllByOwner(user);
        List<WebhookResponse> webhookResponses = new ArrayList<>();
        for(Webhook webhook : webhooks){
            webhookResponses.add(mapToResponse(webhook, false));
        }
        return webhookResponses;
    }


    @Transactional
    public WebhookResponse updateWebhook(Long id, UpdateWebhookRequest request, User user){
        Webhook webhook = getOwnedWebhookOrThrow(id, user);

        if (request.getIsActive() != null){
            webhook.setActive(request.getIsActive());
        }
        if(request.getEventType() != null){
            webhook.setEventType(serializeEventTypes(request.getEventType()));
        }
        if(request.getDescription() != null){
            webhook.setDescription(request.getDescription());
        }
        if (request.getUrl() != null){
            webhook.setUrl(request.getUrl());
        }

        Webhook savedWebhook = webhookRepository.save(webhook);
        return mapToResponse(savedWebhook, false);
    }

    @Transactional
    public void deleteWebhook(Long id, User user){
        Webhook webhook = getOwnedWebhookOrThrow(id, user);
        webhookRepository.delete(webhook);
    }

    @Transactional
    public WebhookResponse regenerateSecret(Long id, User user){
        Webhook webhook = getOwnedWebhookOrThrow(id, user);
        webhook.setSecretKey(SecretKeyGeneratorUtil.generateSecret());
        Webhook savedWebhook = webhookRepository.save(webhook);
        return mapToResponse(savedWebhook, true);
    }

}

