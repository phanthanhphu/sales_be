package org.bsl.sales.error;

import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.List;

public class ApiError {
    private int status;
    private String message;
    private LocalDateTime timestamp;
    private List<String> errors;

    public ApiError(HttpStatus status, String message, List<String> errors) {
        this.status = status.value();
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.errors = errors;
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

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }
}
