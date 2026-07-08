package org.bsl.sales.handler;

import org.bsl.sales.error.ApiError;
import org.bsl.sales.exception.DuplicateSupplierProductException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateSupplierProductException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateSupplierProductException ex) {
        logger.error("Duplicate entry: {}", ex.getMessage());
        ApiError error = new ApiError(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                List.of("Duplicate supplierCode, sapCode, price")
        );
        return json(HttpStatus.BAD_REQUEST, error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        logger.error("Invalid argument: {}", ex.getMessage());
        ApiError error = new ApiError(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                null
        );
        return json(HttpStatus.BAD_REQUEST, error);
    }

    /**
     * Browser/client cancelled an image/download request while Spring was still
     * writing the response. This is not a business error and we must not try to
     * serialize ApiError after the response was already prepared as image/png.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        logger.debug("Client disconnected before response completed: {}", ex.getMessage());
    }

    /**
     * Tomcat client-abort messages are usually wrapped as IOException.
     * Ignore only real disconnect/abort cases; handle other IO errors normally.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> handleIOException(IOException ex) throws IOException {
        if (isClientAbort(ex)) {
            logger.debug("Client aborted connection before response completed: {}", ex.getMessage());
            return null;
        }

        logger.error("I/O error: {}", ex.getMessage(), ex);
        ApiError error = new ApiError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "I/O error occurred",
                null
        );
        return json(HttpStatus.INTERNAL_SERVER_ERROR, error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAllExceptions(Exception ex) {
        if (isClientAbort(ex)) {
            logger.debug("Client aborted connection before response completed: {}", ex.getMessage());
            return null;
        }

        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        ApiError error = new ApiError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error occurred",
                null
        );
        return json(HttpStatus.INTERNAL_SERVER_ERROR, error);
    }

    private ResponseEntity<ApiError> json(HttpStatus status, ApiError error) {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            String normalizedMessage = message == null ? "" : message.toLowerCase();

            if (className.contains("ClientAbortException")
                    || current instanceof AsyncRequestNotUsableException
                    || normalizedMessage.contains("broken pipe")
                    || normalizedMessage.contains("connection reset")
                    || normalizedMessage.contains("connection was aborted")
                    || normalizedMessage.contains("failed to flush")) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }
}
