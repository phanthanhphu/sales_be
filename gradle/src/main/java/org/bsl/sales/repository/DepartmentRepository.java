package org.bsl.sales.repository;

import org.bsl.sales.model.Department;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends MongoRepository<Department, String> {
    boolean existsByDivisionAndDepartmentName(String division, String departmentName);
    Optional<Department> findByDivisionAndDepartmentName(String division, String departmentName);
    List<Department> findByDivisionContainingIgnoreCase(String division);
    List<Department> findByDepartmentNameContainingIgnoreCase(String departmentName);
    List<Department> findByDivisionContainingIgnoreCaseAndDepartmentNameContainingIgnoreCase(String division, String departmentName);
}
