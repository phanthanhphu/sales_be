package org.bsl.sales.service;

import org.bsl.sales.model.Department;
import org.bsl.sales.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DepartmentService {

    @Autowired
    private DepartmentRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

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

    public Page<Department> searchPage(
            String division,
            String departmentName,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String requestedSortBy = sortBy == null ? "" : sortBy.trim();
        String safeSortBy = Set.of("division", "departmentName", "createdAt", "updatedAt")
                .contains(requestedSortBy) ? requestedSortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, safeSortBy);
        if ("createdAt".equals(safeSortBy) && direction == Sort.Direction.DESC) {
            sort = sort.and(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")));
        }
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);

        Query query = new Query();
        String cleanDivision = division == null ? "" : division.trim();
        String cleanDepartmentName = departmentName == null ? "" : departmentName.trim();

        if (!cleanDivision.isEmpty()) {
            query.addCriteria(Criteria.where("division").regex(
                    Pattern.compile(Pattern.quote(cleanDivision), Pattern.CASE_INSENSITIVE)
            ));
        }
        if (!cleanDepartmentName.isEmpty()) {
            query.addCriteria(Criteria.where("departmentName").regex(
                    Pattern.compile(Pattern.quote(cleanDepartmentName), Pattern.CASE_INSENSITIVE)
            ));
        }

        long total = mongoTemplate.count(query, Department.class);
        query.with(pageable);
        List<Department> content = mongoTemplate.find(query, Department.class);
        return new PageImpl<>(content, pageable, total);
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