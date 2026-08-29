package org.bsl.sales.service;

/**
 * Legacy compatibility shim.
 *
 * Product Color Master is no longer an active application service. Product colors,
 * child colors and color images now belong to each BOM. This class intentionally
 * has no Spring stereotype so old source files copied on top of the project cannot
 * reactivate the removed master-data flow.
 */
@Deprecated(forRemoval = true)
public final class ProductColorMasterService {
    private ProductColorMasterService() {
        throw new UnsupportedOperationException("Product Color Master has been replaced by BOM-local colors");
    }
}
