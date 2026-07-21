package org.bsl.sales.dto;

import java.util.ArrayList;
import java.util.List;

public class MasterDataImportResult {

    private String masterData;
    private ImportMode mode;
    private boolean applied;
    private int totalRows;
    private int validRows;
    private int created;
    private int updated;
    private int skipped;
    private int deleted;
    private List<ImportRowError> errors = new ArrayList<>();

    public static MasterDataImportResult rejected(
            String masterData,
            ImportMode mode,
            int totalRows,
            List<ImportRowError> errors
    ) {
        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(masterData);
        result.setMode(mode);
        result.setApplied(false);
        result.setTotalRows(totalRows);
        long invalidDataRows = errors == null ? 0 : errors.stream()
                .map(ImportRowError::getRowNumber)
                .filter(row -> row > 1)
                .distinct()
                .count();
        result.setValidRows(Math.max(0, totalRows - (int) invalidDataRows));
        result.setSkipped(totalRows);
        result.setErrors(errors);
        return result;
    }

    public String getMasterData() {
        return masterData;
    }

    public void setMasterData(String masterData) {
        this.masterData = masterData;
    }

    public ImportMode getMode() {
        return mode;
    }

    public void setMode(ImportMode mode) {
        this.mode = mode;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(boolean applied) {
        this.applied = applied;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public void setValidRows(int validRows) {
        this.validRows = validRows;
    }

    public int getCreated() {
        return created;
    }

    public void setCreated(int created) {
        this.created = created;
    }

    public int getUpdated() {
        return updated;
    }

    public void setUpdated(int updated) {
        this.updated = updated;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public int getDeleted() { return deleted; }

    public void setDeleted(int deleted) { this.deleted = deleted; }

    public List<ImportRowError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportRowError> errors) {
        this.errors = errors == null ? new ArrayList<>() : errors;
    }
}
