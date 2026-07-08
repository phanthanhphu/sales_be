package org.bsl.sales.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class ApiError {

    private int status;
    private String message;
    private LocalDateTime timestamp = LocalDateTime.now();
    private Map<String, String> fieldErrors = new LinkedHashMap<>();

    public ApiError() {
    }

    public ApiError(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors == null ? new LinkedHashMap<>() : fieldErrors;
    }
}
