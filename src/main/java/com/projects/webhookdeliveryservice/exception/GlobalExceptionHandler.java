package com.projects.webhookdeliveryservice.exception;

import com.projects.webhookdeliveryservice.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler{

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException exception,
                                                         HttpServletRequest request){
        return buildErrorResponse(HttpStatus.CONFLICT, exception.getMessage(), request);
    }


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException exception,
                                                                HttpServletRequest request){

        return buildErrorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);

    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException exception,
                                                            HttpServletRequest request){

        return buildErrorResponse(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);

    }

    @ExceptionHandler(InvalidResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResourceAccess(InvalidResourceAccessException exception,
                                                            HttpServletRequest request){
        return buildErrorResponse(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }


    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request) {

        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password.",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception exception,
                                                       HttpServletRequest request){
        log.error("Unhandled exception", exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return respond(HttpStatus.BAD_REQUEST, message, request, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.BAD_REQUEST, "Malformed or invalid JSON request body.", request, headers);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            @NonNull Exception ex, Object body, @NonNull HttpHeaders headers,
            HttpStatusCode statusCode, @NonNull WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        return respond(status, status.getReasonPhrase(), request, headers);
    }

    private ResponseEntity<Object> respond(HttpStatus status, String message,
                                           WebRequest request, HttpHeaders headers) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        ErrorResponse error = ErrorResponse.builder()
                .status(status.value()).error(status.getReasonPhrase())
                .message(message).path(path).timestamp(Instant.now()).build();
        return ResponseEntity.status(status).headers(headers).body(error);
    }


    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request ){

        ErrorResponse error = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(status).body(error);
    }

}
