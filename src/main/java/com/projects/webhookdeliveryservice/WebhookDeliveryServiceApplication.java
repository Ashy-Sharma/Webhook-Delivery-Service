package com.projects.webhookdeliveryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebhookDeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookDeliveryServiceApplication.class, args);
    }

}
