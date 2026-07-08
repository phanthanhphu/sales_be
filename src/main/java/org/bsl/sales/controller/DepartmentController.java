package org.bsl.sales.controller;

import org.bsl.sales.common.socket.AppSocketPublisher;
import org.bsl.sales.model.Department;
import org.bsl.sales.model.User;
import org.bsl.sales.service.DepartmentService;
import org.bsl.sales.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    @Autowired
    private DepartmentService service;

    @Autowired
    private UserService userService;

    @Autowired
    private AppSocketPublisher appSocketPublisher;

    @PostMapping
    public ResponseEntity<?> create(
            @RequestParam String division,
            @RequestParam String departmentName
    ) {
        try {
            if (division == null || division.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", 400, "message", "Division is required"));
            }

            if (departmentName == null || departmentName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", 400, "message", "Department name is required"));
            }

            Department department = service.create(division, departmentName);
            appSocketPublisher.departmentChanged("CREATED", department.getId());

            return ResponseEntity.ok(department);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", 400, "message", ex.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAll() {
        List<Department> departments = service.getAll();
        return ResponseEntity.ok(sortDepartmentsByUpdatedAtDesc(departments));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        Department department = service.getById(id);

        if (department == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "message", "Department not found"));
        }

        return ResponseEntity.ok(department);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable String id,
            @RequestParam String division,
            @RequestParam String departmentName
    ) {
        try {
            if (division == null || division.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", 400, "message", "Division is required"));
            }

            if (departmentName == null || departmentName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", 400, "message", "Department name is required"));
            }

            Department department = service.update(id, division, departmentName);

            if (department == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", 404, "message", "Department not found"));
            }

            appSocketPublisher.departmentChanged("UPDATED", department.getId());

            return ResponseEntity.ok(department);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", 400, "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            String departmentId = id != null ? id.trim() : null;

            if (departmentId == null || departmentId.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", 400, "message", "Department ID is required"));
            }

            Department department = service.getById(departmentId);

            if (department == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", 404, "message", "Department not found"));
            }

            service.delete(departmentId);
            appSocketPublisher.departmentChanged("DELETED", departmentId);

            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "message", "Deleted successfully"
            ));

        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", 500,
                            "message", "Delete failed: " + ex.getMessage()
                    ));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> filter(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String division,
            @RequestParam(required = false) String departmentName,
            @RequestParam(defaultValue = "false") boolean skipDepartmentFilter
    ) {
        try {
            if (userId == null || userId.trim().isEmpty()) {
                List<Department> departments = service.getAll(division, departmentName);

                return ResponseEntity.ok(Map.of(
                        "isAdmin", false,
                        "skipDepartmentFilter", skipDepartmentFilter,
                        "disableDepartmentSearch", true,
                        "departments", sortDepartmentsByUpdatedAtDesc(departments)
                ));
            }

            Optional<User> userOpt = userService.findById(userId.trim());

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "status", 400,
                                "message", "User with ID " + userId + " does not exist"
                        ));
            }

            User user = userOpt.get();

            if (isAdmin(user)) {
                List<Department> departments = service.getAll(division, departmentName);

                return ResponseEntity.ok(Map.of(
                        "isAdmin", true,
                        "skipDepartmentFilter", true,
                        "disableDepartmentSearch", false,
                        "departments", sortDepartmentsByUpdatedAtDesc(departments)
                ));
            }

            if (skipDepartmentFilter) {
                List<Department> departments = service.getAll(division, departmentName);

                return ResponseEntity.ok(Map.of(
                        "isAdmin", false,
                        "skipDepartmentFilter", true,
                        "disableDepartmentSearch", false,
                        "departments", sortDepartmentsByUpdatedAtDesc(departments)
                ));
            }

            String departmentId = user.getDepartmentId();

            if (departmentId == null || departmentId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "status", 400,
                                "message", "User does not belong to any department"
                        ));
            }

            Department department = service.getById(departmentId.trim());

            if (department == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", 404, "message", "Department not found"));
            }

            return ResponseEntity.ok(Map.of(
                    "isAdmin", false,
                    "skipDepartmentFilter", false,
                    "disableDepartmentSearch", true,
                    "departments", List.of(department)
            ));

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", 500,
                            "message", "Failed to search departments: " + ex.getMessage()
                    ));
        }
    }

    private List<Department> sortDepartmentsByUpdatedAtDesc(List<Department> departments) {
        if (departments == null || departments.isEmpty()) {
            return departments;
        }

        departments.sort(
                Comparator.comparing(
                        Department::getUpdatedAt,
                        Comparator.nullsLast(LocalDateTime::compareTo)
                )
                        .thenComparing(
                                Department::getCreatedAt,
                                Comparator.nullsLast(LocalDateTime::compareTo)
                        )
                        .reversed()
        );

        return departments;
    }

    private boolean isAdmin(User user) {
        if (user.getRole() == null) {
            return false;
        }

        String role = user.getRole().trim();

        return "Admin".equalsIgnoreCase(role)
                || "ADMIN".equalsIgnoreCase(role)
                || "ROLE_ADMIN".equalsIgnoreCase(role);
    }
}