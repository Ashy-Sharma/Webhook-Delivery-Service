package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.exception.InvalidEventTypeException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class EventTypeService {

    private final List<String> allowedEventTypes;

    public EventTypeService(@Value("${webhook.event-types}") String configuredEventTypes) {
        this.allowedEventTypes = Arrays.stream(configuredEventTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    @PostConstruct
    void validateConfiguration() {
        if (allowedEventTypes.isEmpty()) {
            throw new IllegalArgumentException("webhook.event-types must contain at least one event type");
        }
    }

    public List<String> getAllowedEventTypes() {
        return allowedEventTypes;
    }

    public void validate(String eventType) {
        if (eventType == null || !allowedEventTypes.contains(eventType)) {
            throw new InvalidEventTypeException(eventType, allowedEventTypes);
        }
    }

    public void validateAll(Iterable<String> eventTypes) {
        for (String eventType : eventTypes) {
            validate(eventType);
        }
    }
}
