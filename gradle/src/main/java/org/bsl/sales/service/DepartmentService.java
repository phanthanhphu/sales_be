package org.bsl.sales.service;

import org.bsl.sales.model.Department;
import org.bsl.sales.repository.DepartmentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DepartmentService {
    private final DepartmentRepository departmentRepository;

    public DepartmentService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    public Department create(String division, String departmentName) {
        String cleanDivision = required(division, "Division is required");
        String cleanName = required(departmentName, "Department name is required");

        if (departmentRepository.existsByDivisionAndDepartmentName(cleanDivision, cleanName)) {
            throw new IllegalArgumentException("Department already exists in this division");
        }

        LocalDateTime now = LocalDateTime.now();
        Department department = new Department();
        department.setId(UUID.randomUUID().toString());
        department.setDivision(cleanDivision);
        department.setDepartmentName(cleanName);
        department.setCreatedAt(now);
        department.setUpdatedAt(now);

        try {
            return departmentRepository.save(department);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("Department already exists in this division");
        }
    }

    public List<Department> getAll(String division, String departmentName) {
        String cleanDivision = trim(division);
        String cleanName = trim(departmentName);

        List<Department> departments;
        if (!StringUtils.hasText(cleanDivision) && !StringUtils.hasText(cleanName)) {
            departments = departmentRepository.findAll();
        } else if (StringUtils.hasText(cleanDivision) && StringUtils.hasText(cleanName)) {
            departments = departmentRepository
                    .findByDivisionContainingIgnoreCaseAndDepartmentNameContainingIgnoreCase(cleanDivision, cleanName);
        } else if (StringUtils.hasText(cleanDivision)) {
            departments = departmentRepository.findByDivisionContainingIgnoreCase(cleanDivision);
        } else {
            departments = departmentRepository.findByDepartmentNameContainingIgnoreCase(cleanName);
        }

        return departments.stream()
                .sorted(Comparator.comparing(Department::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Department::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Department getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return departmentRepository.findById(id.trim()).orElse(null);
    }

    public Department requireById(String id) {
        Department department = getById(id);
        if (department == null) {
            throw new IllegalArgumentException("Department not found");
        }
        return department;
    }

    public Department update(String id, String division, String departmentName) {
        Department existing = requireById(id);
        String cleanDivision = required(division, "Division is required");
        String cleanName = required(departmentName, "Department name is required");

        Optional<Department> duplicate = departmentRepository.findByDivisionAndDepartmentName(cleanDivision, cleanName);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(existing.getId())) {
            throw new IllegalArgumentException("Department already exists in this division");
        }

        existing.setDivision(cleanDivision);
        existing.setDepartmentName(cleanName);
        existing.setUpdatedAt(LocalDateTime.now());
        try {
            return departmentRepository.save(existing);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("Department already exists in this division");
        }
    }

    public void delete(String id) {
        Department department = requireById(id);
        departmentRepository.delete(department);
    }

    private String required(String value, String message) {
        String trimmed = trim(value);
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
