package com.projects.webhookdeliveryservice.exception;

public class InvalidResourceAccessException extends RuntimeException {
    public InvalidResourceAccessException(String message) {
        super(message);
    }
}
