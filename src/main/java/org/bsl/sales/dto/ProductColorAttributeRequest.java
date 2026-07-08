package org.bsl.sales.dto;

import jakarta.validation.constraints.Size;

/** A reusable Child Color belonging to one Product / Style Color. */
public record ProductColorAttributeRequest(
        @Size(max = 100, message = "Child color id must not exceed 100 characters") String id,
        @Size(max = 300, message = "Child color must not exceed 300 characters") String childColor
) { }
