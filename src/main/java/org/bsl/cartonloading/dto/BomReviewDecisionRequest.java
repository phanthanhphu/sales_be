package org.bsl.cartonloading.dto;

/** Optional note recorded when BOM applies or sends an MPR change back to Sales. */
public record BomReviewDecisionRequest(String comment) { }
