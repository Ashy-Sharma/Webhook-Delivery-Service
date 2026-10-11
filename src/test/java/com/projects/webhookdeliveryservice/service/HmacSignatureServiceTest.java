package com.projects.webhookdeliveryservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignatureServiceTest {

    private final HmacSignatureService service = new HmacSignatureService();

    @Test
    void signIsDeterministicForTheSamePayloadAndKey() {
        String first = service.sign("secret-key", "{\"event\":\"order.created\"}");

        assertThat(service.sign("secret-key", "{\"event\":\"order.created\"}"))
                .isEqualTo(first);
    }

    @Test
    void differentKeysProduceDifferentSignatures() {
        String first = service.sign("first-key", "payload");

        assertThat(service.sign("second-key", "payload")).isNotEqualTo(first);
    }

    @Test
    void verifyAcceptsAnUntamperedPayloadAndRejectsTampering() {
        String payload = "{\"orderId\":42}";
        String signature = service.sign("secret-key", payload);

        assertThat(service.verify("secret-key", payload, signature)).isTrue();
        assertThat(service.verify("secret-key", "{\"orderId\":43}", signature)).isFalse();
        assertThat(service.verify("secret-key", payload, null)).isFalse();
    }
}
