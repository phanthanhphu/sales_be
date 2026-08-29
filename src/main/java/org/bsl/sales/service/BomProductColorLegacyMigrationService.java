package org.bsl.sales.service;

import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.ProductColorAttribute;
import org.bsl.sales.model.ProductColorMaster;
import org.bsl.sales.repository.ProductColorMasterRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One-way compatibility bridge for deployments that previously stored Product
 * Color data in Product Color Master. New runtime logic never reads the master.
 * The first time an old BOM is loaded, master identity/Child Colors/image are
 * copied into that BOM, then the legacy link is cleared when migration succeeds.
 */
@Service
public class BomProductColorLegacyMigrationService {
    private final ProductColorMasterRepository masterRepository;
    private final BomFileStorageService fileStorage;

    public BomProductColorLegacyMigrationService(
            ProductColorMasterRepository masterRepository,
            BomFileStorageService fileStorage
    ) {
        this.masterRepository = masterRepository;
        this.fileStorage = fileStorage;
    }

    public boolean migrateIfNeeded(BomDocument bom) {
        if (bom == null || bom.getProductColors() == null || bom.getProductColors().isEmpty()) return false;
        if (bom.getAttachments() == null) bom.setAttachments(new ArrayList<>());

        boolean changed = false;
        for (BomProductColor productColor : bom.getProductColors()) {
            if (productColor == null) continue;
            String masterId = trim(productColor.getProductColorMasterId());
            if (masterId.isBlank()) continue;

            Optional<ProductColorMaster> optional = masterRepository.findById(masterId);
            if (optional.isEmpty()) {
                // The legacy record no longer exists. The BOM identity remains valid and
                // line values can rebuild local Child Colors during full hydration.
                productColor.setProductColorMasterId(null);
                changed = true;
                continue;
            }

            ProductColorMaster master = optional.get();
            changed |= fillBlankIdentity(productColor, master);
            changed |= mergeChildColors(productColor, master.getChildColors());

            boolean imageMigrated = true;
            if (!hasBomColorImage(bom, productColor.getId()) && master.hasImage()) {
                imageMigrated = copyMasterImageToBom(bom, productColor, master);
                changed |= imageMigrated;
            }

            // If an image copy fails, keep the hidden pointer so a later load can retry.
            if (imageMigrated) {
                productColor.setProductColorMasterId(null);
                changed = true;
            }
        }
        return changed;
    }

    private boolean fillBlankIdentity(BomProductColor color, ProductColorMaster master) {
        boolean changed = false;
        if (blank(color.getColorName()) && !blank(master.getProductColor())) { color.setColorName(trim(master.getProductColor())); changed = true; }
        if (blank(color.getPatternNumber()) && !blank(master.getPatternNumber())) { color.setPatternNumber(trim(master.getPatternNumber())); changed = true; }
        if (blank(color.getSeason()) && !blank(master.getSeason())) { color.setSeason(trim(master.getSeason())); changed = true; }
        if (blank(color.getStyleNumber()) && !blank(master.getStyleNumber())) { color.setStyleNumber(trim(master.getStyleNumber())); changed = true; }
        return changed;
    }

    private boolean mergeChildColors(BomProductColor color, List<ProductColorAttribute> source) {
        if (color.getChildColors() == null) color.setChildColors(new ArrayList<>());
        LinkedHashMap<String, ProductColorAttribute> byText = new LinkedHashMap<>();
        for (ProductColorAttribute item : safe(color.getChildColors())) {
            if (item == null || blank(item.getChildColor())) continue;
            if (blank(item.getId())) item.setId(UUID.randomUUID().toString());
            byText.putIfAbsent(normalize(item.getChildColor()), item);
        }

        boolean changed = false;
        for (ProductColorAttribute legacy : safe(source)) {
            if (legacy == null || blank(legacy.getChildColor())) continue;
            String key = normalize(legacy.getChildColor());
            if (byText.containsKey(key)) continue;
            ProductColorAttribute copy = new ProductColorAttribute();
            copy.setId(blank(legacy.getId()) ? UUID.randomUUID().toString() : trim(legacy.getId()));
            copy.setChildColor(trim(legacy.getChildColor()));
            color.getChildColors().add(copy);
            byText.put(key, copy);
            changed = true;
        }
        return changed;
    }

    private boolean copyMasterImageToBom(BomDocument bom, BomProductColor color, ProductColorMaster master) {
        try {
            BomFileStorageService.StoredFile copied = fileStorage.copyStoredFile(
                    master.getImageStoredFileName(),
                    firstNonBlank(master.getImageFileName(), "product-color-image"),
                    master.getImageContentType(),
                    master.getImageSize()
            );
            BomAttachment attachment = new BomAttachment();
            attachment.setId(UUID.randomUUID().toString());
            attachment.setOriginalFileName(copied.originalFileName());
            attachment.setStoredFileName(copied.storedFileName());
            attachment.setContentType(copied.contentType());
            attachment.setSize(copied.size());
            attachment.setScope("COLOR");
            attachment.setProductColorId(color.getId());
            attachment.setColorKey(color.getColorName());
            attachment.setImportedFromExcel(false);
            attachment.setDownloadUrl("/api/boms/" + bom.getId() + "/attachments/" + attachment.getId() + "/download");
            attachment.setUploadedBy("PRODUCT_COLOR_MASTER_MIGRATION");
            attachment.setUploadedAt(LocalDateTime.now());
            bom.getAttachments().add(attachment);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasBomColorImage(BomDocument bom, String productColorId) {
        for (BomAttachment attachment : safe(bom.getAttachments())) {
            if (attachment == null) continue;
            if (!"COLOR".equalsIgnoreCase(trim(attachment.getScope()))) continue;
            if (!Objects.equals(trim(productColorId), trim(attachment.getProductColorId()))) continue;
            String contentType = trim(attachment.getContentType()).toLowerCase(Locale.ROOT);
            String name = trim(attachment.getOriginalFileName()).toLowerCase(Locale.ROOT);
            if (contentType.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|emf|wmf)$")) return true;
        }
        return false;
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private boolean blank(String value) { return trim(value).isBlank(); }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String normalize(String value) { return trim(value).toUpperCase(Locale.ROOT); }
    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return trim(value);
        return "";
    }
}
