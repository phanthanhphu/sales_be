package org.bsl.sales.config;

import org.bsl.sales.exception.MprExcelExportIntegrityException;
import org.bsl.sales.exception.BomExcelDuplicateProductColorException;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        org.bsl.sales.controller.OrderController.class,
        org.bsl.sales.controller.BomController.class,
        org.bsl.sales.controller.MprController.class
})
public class OrderBomMprExceptionHandler {

    @ExceptionHandler(BomExcelDuplicateProductColorException.class)
    public ResponseEntity<Map<String, Object>> duplicateProductColorConfirmation(
            BomExcelDuplicateProductColorException ex
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("code", BomExcelDuplicateProductColorException.CODE);
        body.put("message", ex.getMessage());
        body.put("duplicates", ex.getDuplicates());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(OrderBomMprNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(OrderBomMprNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(OrderBomMprValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(OrderBomMprValidationException ex) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MprExcelExportIntegrityException.class)
    public ResponseEntity<Map<String, Object>> mprExcelIntegrity(MprExcelExportIntegrityException ex) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, DuplicateKeyException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException ex) {
        return response(HttpStatus.CONFLICT, "Data was changed by another user or violates a unique constraint. Refresh and try again.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("message", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
