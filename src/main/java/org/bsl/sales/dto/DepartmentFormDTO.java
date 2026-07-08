package org.bsl.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class DepartmentFormDTO {

    @Schema(description = "Department document id", example = "dept-hr")
    private String id;

    @Schema(description = "Division code/name", example = "HR")
    @NotBlank(message = "Division must not be blank")
    @Size(max = 100, message = "Division must not exceed 100 characters")
    private String division;

    @Schema(description = "Department name", example = "Phòng Nhân sự")
    @NotBlank(message = "Department name must not be blank")
    @Size(max = 150, message = "Department name must not exceed 150 characters")
    private String departmentName;

    private List<String> noticeIds;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDivision() {
        return division;
    }

    public void setDivision(String division) {
        this.division = division;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public List<String> getNoticeIds() {
        return noticeIds;
    }

    public void setNoticeIds(List<String> noticeIds) {
        this.noticeIds = noticeIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
