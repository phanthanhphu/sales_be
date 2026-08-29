package org.bsl.sales.controller;

/**
 * Legacy compatibility shim.
 *
 * Intentionally has no Spring MVC controller annotation. Product Color Master endpoints
 * were removed; product colors are managed inside each BOM.
 */
@Deprecated(forRemoval = true)
public final class ProductColorMasterController {
    private ProductColorMasterController() {
    }
}
