package org.bsl.sales.controller;

import org.bsl.sales.model.Department;
import org.bsl.sales.model.User;
import org.bsl.sales.service.DepartmentService;
import org.bsl.sales.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {
    private final DepartmentService departmentService;
    private final UserService userService;

    public DepartmentController(DepartmentService departmentService, UserService userService) {
        this.departmentService = departmentService;
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestParam String division,
            @RequestParam String departmentName,
            Authentication authentication
    ) {
        // The first Department can be created before the first administrator exists.
        if (userService.hasUsers()) {
            ApiAccess.requireAdmin(authentication);
        }
        Department department = departmentService.create(division, departmentName);
        return ResponseEntity.status(HttpStatus.CREATED).body(department);
    }

    @GetMapping
    public List<Department> getAll(
            @RequestParam(required = false) String division,
            @RequestParam(required = false) String departmentName
    ) {
        return departmentService.getAll(division, departmentName);
    }

    @GetMapping("/{id}")
    public Department getById(@PathVariable String id) {
        Department department = departmentService.getById(id);
        if (department == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found");
        }
        return department;
    }

    @PutMapping("/{id}")
    public Department update(
            @PathVariable String id,
            @RequestParam String division,
            @RequestParam String departmentName,
            Authentication authentication
    ) {
        ApiAccess.requireAdmin(authentication);
        return departmentService.update(id, division, departmentName);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, Authentication authentication) {
        ApiAccess.requireAdmin(authentication);
        Department department = departmentService.getById(id);
        if (department == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found");
        }

        long userCount = userService.countByDepartmentId(department.getId());
        if (userCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot delete this department because " + userCount + " user(s) still belong to it"
            );
        }

        departmentService.delete(id);
        return Map.of("status", HttpStatus.OK.value(), "message", "Department deleted successfully");
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String division,
            @RequestParam(required = false) String departmentName,
            @RequestParam(defaultValue = "false") boolean skipDepartmentFilter
    ) {
        List<Department> all = departmentService.getAll(division, departmentName);
        Map<String, Object> response = new LinkedHashMap<>();

        if (userId == null || userId.isBlank()) {
            response.put("isAdmin", false);
            response.put("skipDepartmentFilter", skipDepartmentFilter);
            response.put("disableDepartmentSearch", true);
            response.put("departments", all);
            return response;
        }

        User user = userService.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User does not exist"));
        boolean isAdmin = "ADMIN".equals(UserService.normalizeRole(user.getRole()));

        if (isAdmin || skipDepartmentFilter) {
            response.put("isAdmin", isAdmin);
            response.put("skipDepartmentFilter", true);
            response.put("disableDepartmentSearch", false);
            response.put("departments", all);
            return response;
        }

        Department ownDepartment = departmentService.getById(user.getDepartmentId());
        if (ownDepartment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The user's department was not found");
        }

        response.put("isAdmin", false);
        response.put("skipDepartmentFilter", false);
        response.put("disableDepartmentSearch", true);
        response.put("departments", List.of(ownDepartment));
        return response;
    }
}
