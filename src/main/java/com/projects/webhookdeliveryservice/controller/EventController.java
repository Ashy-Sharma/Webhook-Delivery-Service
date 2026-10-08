package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.dto.request.PublishEventRequest;
import com.projects.webhookdeliveryservice.dto.response.EventPublishResponse;
import com.projects.webhookdeliveryservice.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventPublishResponse> publishEvent(
            @Valid @RequestBody PublishEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.publishEvent(request));
    }
}
