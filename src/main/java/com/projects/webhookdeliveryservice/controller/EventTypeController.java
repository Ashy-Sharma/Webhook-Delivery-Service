package com.projects.webhookdeliveryservice.controller;

import com.projects.webhookdeliveryservice.service.EventTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/event-types")
public class EventTypeController {

    private final EventTypeService eventTypeService;

    @GetMapping
    public List<String> getEventTypes() {
        return eventTypeService.getAllowedEventTypes();
    }
}
