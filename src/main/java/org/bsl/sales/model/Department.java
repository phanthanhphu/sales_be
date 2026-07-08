package org.bsl.sales.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "departments")
public class Department {

    @Id
    private String id;

    private String division;

    private String departmentName;

    private List<String> noticeIds = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Department() {
    }

    public Department(String id, String division, String departmentName, List<String> noticeIds, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.division = division;
        this.departmentName = departmentName;
        this.noticeIds = noticeIds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public List<String> getNoticeIds() { return noticeIds; }
    public void setNoticeIds(List<String> noticeIds) { this.noticeIds = noticeIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
