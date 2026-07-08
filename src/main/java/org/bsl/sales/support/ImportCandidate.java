package org.bsl.sales.support;

public class ImportCandidate<T> {

    private final int rowNumber;
    private final T value;

    public ImportCandidate(int rowNumber, T value) {
        this.rowNumber = rowNumber;
        this.value = value;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public T getValue() {
        return value;
    }
}
