package org.bsl.sales.dto;

import jakarta.validation.constraints.Size;

/** A Child Color belonging to one Product / Style Color inside one BOM. */
public record ProductColorAttributeRequest(
        @Size(max = 100, message = "Child color id must not exceed 100 characters") String id,
        @Size(max = 300, message = "Child color must not exceed 300 characters") String childColor
) { }
