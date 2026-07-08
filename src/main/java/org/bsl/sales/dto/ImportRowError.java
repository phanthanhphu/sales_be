package org.bsl.sales.dto;

public class ImportRowError {

    private int rowNumber;
    private String field;
    private String message;

    public ImportRowError() {
    }

    public ImportRowError(int rowNumber, String field, String message) {
        this.rowNumber = rowNumber;
        this.field = field;
        this.message = message;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public void setRowNumber(int rowNumber) {
        this.rowNumber = rowNumber;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
