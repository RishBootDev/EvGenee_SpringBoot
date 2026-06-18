package com.voltx.evgenee.exceptions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Date;
import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private ResponseEntity<ErrorResponse> buildErrorResponse(String userMessage, Exception ex,
                                                             WebRequest request, HttpStatus status,
                                                             String errorCode) {
        ErrorResponse errorResponse = new ErrorResponse(
                new Date(),
                status.value(),
                errorCode,
                userMessage,
                request.getDescription(false)
        );
        return new ResponseEntity<>(errorResponse, status);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, WebRequest request) {
        log.error("ResourceNotFoundException: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                "The requested resource could not be found. Please verify the details and try again.",
                ex, request, HttpStatus.NOT_FOUND, ex.getErrorCode());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, WebRequest request) {
        log.error("BadRequestException: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                "Invalid request. Please check your input and try again.",
                ex, request, HttpStatus.BAD_REQUEST, ex.getErrorCode());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, WebRequest request) {
        log.error("UnauthorizedException: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                "You are not authorized to perform this action.",
                ex, request, HttpStatus.UNAUTHORIZED, ex.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, WebRequest request) {
        log.error("ValidationException: {}", ex.getMessage(), ex);
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return buildErrorResponse(
                errors, ex, request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        log.warn("MethodArgumentTypeMismatchException: {}", ex.getMessage());
        String userMessage = String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
                ex.getValue(), ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        return buildErrorResponse(
                userMessage, ex, request, HttpStatus.BAD_REQUEST, "TYPE_MISMATCH_ERROR");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
        log.error("Unhandled Exception: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                "Something went wrong on our side. Please try again later.",
                ex, request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
    }
}