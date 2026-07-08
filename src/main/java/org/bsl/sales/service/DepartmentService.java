package org.bsl.sales.service;

import org.bsl.sales.model.Department;
import org.bsl.sales.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DepartmentService {

    @Autowired
    private DepartmentRepository repository;

    public Department create(String division, String departmentName) {
        division = division != null ? division.trim() : "";
        departmentName = departmentName != null ? departmentName.trim() : "";

        if (division.isEmpty()) {
            throw new RuntimeException("Division is required");
        }

        if (departmentName.isEmpty()) {
            throw new RuntimeException("Department name is required");
        }

        boolean exists = repository.existsByDivisionAndDepartmentName(division, departmentName);

        if (exists) {
            throw new RuntimeException("Department already exists in this division");
        }

        Department department = new Department();
        department.setDivision(division);
        department.setDepartmentName(departmentName);

        LocalDateTime now = LocalDateTime.now();
        department.setCreatedAt(now);
        department.setUpdatedAt(now);

        return repository.save(department);
    }

    public List<Department> getAll() {
        return repository.findAll();
    }

    public List<Department> getAll(String division, String departmentName) {
        division = division != null ? division.trim() : "";
        departmentName = departmentName != null ? departmentName.trim() : "";

        if (division.isEmpty() && departmentName.isEmpty()) {
            return repository.findAll();
        }

        if (!division.isEmpty() && departmentName.isEmpty()) {
            return repository.findByDivisionContainingIgnoreCase(division);
        }

        if (division.isEmpty()) {
            return repository.findByDepartmentNameContainingIgnoreCase(departmentName);
        }

        return repository
                .findByDivisionContainingIgnoreCaseAndDepartmentNameContainingIgnoreCase(
                        division,
                        departmentName
                );
    }

    public List<Department> search(
            String division,
            String departmentName,
            int page,
            int size
    ) {
        List<Department> result = getAll(division, departmentName);

        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = result.isEmpty() ? 1 : result.size();
        }

        int fromIndex = page * size;

        if (fromIndex >= result.size()) {
            return List.of();
        }

        int toIndex = Math.min(fromIndex + size, result.size());

        return result.subList(fromIndex, toIndex);
    }

    public Department getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }

        return repository.findById(id.trim()).orElse(null);
    }

    public Department update(String id, String division, String departmentName) {
        division = division != null ? division.trim() : "";
        departmentName = departmentName != null ? departmentName.trim() : "";

        if (division.isEmpty()) {
            throw new RuntimeException("Division is required");
        }

        if (departmentName.isEmpty()) {
            throw new RuntimeException("Department name is required");
        }

        Optional<Department> optional = repository.findById(id);

        if (optional.isEmpty()) {
            return null;
        }

        Optional<Department> duplicate =
                repository.findByDivisionAndDepartmentName(division, departmentName);

        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            throw new RuntimeException("Department already exists in this division");
        }

        Department existing = optional.get();
        existing.setDivision(division);
        existing.setDepartmentName(departmentName);
        existing.setUpdatedAt(LocalDateTime.now());

        return repository.save(existing);
    }

    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new RuntimeException("Department not found");
        }

        repository.deleteById(id);
    }

    public Department findByDepartmentName(String departmentName) {
        return repository.findByDepartmentNameIgnoreCase(departmentName).orElse(null);
    }
}