package com.projects.webhookdeliveryservice.service;

import com.projects.webhookdeliveryservice.exception.InvalidEventTypeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeServiceTest {

    @Test
    void trimsAndExposesConfiguredTypesInConfiguredOrder() {
        EventTypeService service = new EventTypeService(" order.created, user.signup,order.created ");

        assertThat(service.getAllowedEventTypes()).containsExactly("order.created", "user.signup");
        service.validateAll(java.util.List.of("order.created", "user.signup"));
    }

    @Test
    void rejectsUnknownTypesAndListsAllowedTypesInTheError() {
        EventTypeService service = new EventTypeService("order.created,user.signup");

        assertThatThrownBy(() -> service.validate("ordr.created"))
                .isInstanceOf(InvalidEventTypeException.class)
                .hasMessage("Unknown event type: ordr.created. Allowed types: [order.created, user.signup]");
    }
}
