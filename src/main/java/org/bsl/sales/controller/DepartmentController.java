package org.bsl.sales.controller;

import org.bsl.sales.common.socket.AppSocketPublisher;
import org.bsl.sales.model.Department;
import org.bsl.sales.model.User;
import org.bsl.sales.service.DepartmentService;
import org.bsl.sales.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
            @RequestParam(defaultValue = "false") boolean skipDepartmentFilter,
            @RequestParam(defaultValue = "false") boolean paged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        try {
            if (userId == null || userId.trim().isEmpty()) {
                Page<Department> departments = departmentPage(
                        division, departmentName, paged, page, size, sortBy, sortDir
                );
                return ResponseEntity.ok(pageResponse(
                        false, skipDepartmentFilter, true, departments
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
                Page<Department> departments = departmentPage(
                        division, departmentName, paged, page, size, sortBy, sortDir
                );
                return ResponseEntity.ok(pageResponse(
                        true, true, false, departments
                ));
            }

            if (skipDepartmentFilter) {
                Page<Department> departments = departmentPage(
                        division, departmentName, paged, page, size, sortBy, sortDir
                );
                return ResponseEntity.ok(pageResponse(
                        false, true, false, departments
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

            int safePage = Math.max(0, page);
            int safeSize = Math.max(1, Math.min(size, 200));
            List<Department> content = safePage == 0 ? List.of(department) : List.of();
            Page<Department> departments = new PageImpl<>(
                    content,
                    PageRequest.of(safePage, safeSize),
                    1
            );
            return ResponseEntity.ok(pageResponse(
                    false, false, true, departments
            ));

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", 500,
                            "message", "Failed to search departments: " + ex.getMessage()
                    ));
        }
    }

    private Page<Department> departmentPage(
            String division,
            String departmentName,
            boolean paged,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        if (paged) {
            return service.searchPage(division, departmentName, page, size, sortBy, sortDir);
        }

        List<Department> departments = sortDepartmentsByUpdatedAtDesc(
                service.getAll(division, departmentName)
        );
        int legacySize = Math.max(1, departments.size());
        return new PageImpl<>(departments, PageRequest.of(0, legacySize), departments.size());
    }

    private Map<String, Object> pageResponse(
            boolean isAdmin,
            boolean skipDepartmentFilter,
            boolean disableDepartmentSearch,
            Page<Department> page
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("isAdmin", isAdmin);
        response.put("skipDepartmentFilter", skipDepartmentFilter);
        response.put("disableDepartmentSearch", disableDepartmentSearch);
        response.put("departments", page.getContent());
        response.put("page", page.getNumber());
        response.put("size", page.getSize());
        response.put("totalElements", page.getTotalElements());
        response.put("totalPages", page.getTotalPages());
        response.put("first", page.isFirst());
        response.put("last", page.isLast());
        return response;
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