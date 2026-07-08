package org.bsl.sales.controller;

import org.bsl.sales.model.AuditLog;
import org.bsl.sales.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<?> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {

        Page<AuditLog> logs = auditLogService.getAll(page, size);

        return ResponseEntity.ok(logs);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {

        Page<AuditLog> logs = auditLogService.search(username, page, size);

        return ResponseEntity.ok(logs);
    }

}