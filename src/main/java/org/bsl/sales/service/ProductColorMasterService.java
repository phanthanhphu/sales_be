package org.bsl.sales.service;

import org.bsl.sales.dto.ProductColorAttributeRequest;
import org.bsl.sales.dto.ProductColorMasterRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.ProductColorAttribute;
import org.bsl.sales.model.ProductColorMaster;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.ProductColorMasterRepository;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Product / Style Color master.
 *
 * The master deliberately stores only Child Colors. Material Type, MAT FULL
 * DESCRIPTION and Packing belong to BOM lines, not to Product Color Master.
 * Every BOM line color value links to one childColors[].id through childColorId.
 */
@Service
public class ProductColorMasterService {
    private final ProductColorMasterRepository repository;
    private final BomDocumentRepository bomRepository;
    private final BomFileStorageService fileStorage;

    public ProductColorMasterService(
            ProductColorMasterRepository repository,
            BomDocumentRepository bomRepository,
            BomFileStorageService fileStorage
    ) {
        this.repository = repository;
        this.bomRepository = bomRepository;
        this.fileStorage = fileStorage;
    }

    public Page<ProductColorMaster> list(
            String productColor,
            String season,
            String patternNumber,
            String styleName,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<ProductColorMaster> result;
        if (blank(productColor) && blank(season) && blank(patternNumber) && blank(styleName)) {
            result = repository.findAll(pageable);
        } else {
            result = repository.findByProductColorContainingIgnoreCaseOrSeasonContainingIgnoreCaseOrPatternNumberContainingIgnoreCaseOrStyleNameContainingIgnoreCase(
                    trim(productColor), trim(season), trim(patternNumber), trim(styleName), pageable
            );
        }
        return result.map(item -> ensureImageFromLegacyBom(ensureSourceBomDetails(ensureChildColorIds(item))));
    }

    public ProductColorMaster get(String id) {
        ProductColorMaster entity = repository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Product Color Master not found"));
        return ensureImageFromLegacyBom(ensureSourceBomDetails(ensureChildColorIds(entity)));
    }

    public ProductColorMaster create(ProductColorMasterRequest request) {
        ProductColorMaster entity = fromRequest(new ProductColorMaster(), request);
        if (repository.findByMasterKey(entity.getMasterKey()).isPresent()) {
            throw new MasterDataConflictException("Product Color already exists for this Season / Pattern / Style");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    public ProductColorMaster update(String id, ProductColorMasterRequest request) {
        ProductColorMaster entity = get(id);
        fromRequest(entity, request);
        repository.findByMasterKey(entity.getMasterKey())
                .filter(item -> !item.getId().equals(entity.getId()))
                .ifPresent(item -> {
                    throw new MasterDataConflictException("Product Color already exists for this Season / Pattern / Style");
                });
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public void delete(String id) {
        ProductColorMaster entity = get(id);
        if (bomRepository.existsByProductColorsProductColorMasterId(entity.getId())) {
            throw new MasterDataConflictException(
                    "Cannot delete Product Color because one or more BOMs are linked to it. Remove or change the BOM link first."
            );
        }
        repository.delete(entity);
        fileStorage.deleteQuietly(entity.getImageStoredFileName());
    }

    /**
     * Stores one canonical Product Color image. BOMs never own a copied image;
     * they show this file through productColorMasterId.
     */
    public ProductColorMaster uploadImage(String id, MultipartFile file) {
        validateImage(file);
        ProductColorMaster entity = get(id);
        BomFileStorageService.StoredFile stored = fileStorage.store(file);
        String previousStoredFileName = entity.getImageStoredFileName();

        entity.setImageStoredFileName(stored.storedFileName());
        entity.setImageFileName(stored.originalFileName());
        entity.setImageContentType(stored.contentType());
        entity.setImageSize(stored.size());
        entity.setImageUpdatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        ProductColorMaster saved = repository.save(entity);
        fileStorage.deleteQuietly(previousStoredFileName);
        return saved;
    }

    public ProductColorImageResource downloadImage(String id) {
        ProductColorMaster entity = get(id);
        if (blank(entity.getImageStoredFileName())) {
            throw new MasterDataNotFoundException("Product Color image not found");
        }
        Resource resource = fileStorage.load(entity.getImageStoredFileName());
        return new ProductColorImageResource(
                resource,
                firstNonBlank(entity.getImageFileName(), "product-color-image"),
                entity.getImageContentType()
        );
    }

    public void deleteImage(String id) {
        ProductColorMaster entity = get(id);
        String previousStoredFileName = entity.getImageStoredFileName();
        if (blank(previousStoredFileName)) return;

        entity.setImageStoredFileName(null);
        entity.setImageFileName(null);
        entity.setImageContentType(null);
        entity.setImageSize(0);
        entity.setImageUpdatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        fileStorage.deleteQuietly(previousStoredFileName);
    }

    /**
     * Called after BOM Excel import/replacement and after a normal BOM save.
     * The operation keeps every existing reusable Child Color, adds new values
     * discovered in the BOM, and links each BOM material line to the stable
     * Child Color id.
     */
    public void synchronizeFromBom(BomDocument bom) {
        if (bom == null) return;

        for (BomProductColor productColor : safe(bom.getProductColors())) {
            if (productColor == null || blank(productColor.getColorName())) continue;

            ProductColorMaster candidate = candidateFromBom(bom, productColor);
            ProductColorMaster master = upsertCandidate(candidate, productColor.getProductColorMasterId());
            master = ensureLegacyImageFromBom(master, bom, productColor);
            productColor.setProductColorMasterId(master.getId());
            linkBomValuesToMaster(bom, productColor, master);
        }
    }

    /** Returns the master selected by a manual BOM Product Color action. */
    public ProductColorMaster resolve(String id) {
        return get(id);
    }

    /**
     * Finds a Child Color by id first, then by readable color value. Returns
     * null when the master or value does not exist so callers can apply their
     * own strict/legacy behaviour.
     */
    public ProductColorAttribute findChildColor(String masterId, String childColorId, String childColor) {
        if (blank(masterId)) return null;
        Optional<ProductColorMaster> masterOptional = repository.findById(trim(masterId));
        if (masterOptional.isEmpty()) return null;
        ProductColorMaster master = ensureChildColorIds(masterOptional.get());

        String requestedId = trim(childColorId);
        if (!requestedId.isBlank()) {
            for (ProductColorAttribute item : safe(master.getChildColors())) {
                if (item != null && requestedId.equals(trim(item.getId()))) return item;
            }
        }

        String requestedColor = normalize(childColor);
        if (!requestedColor.isBlank()) {
            for (ProductColorAttribute item : safe(master.getChildColors())) {
                if (item != null && requestedColor.equals(normalize(item.getChildColor()))) return item;
            }
        }
        return null;
    }

    /**
     * A manual BOM Product Color can reuse an existing Product Color Master.
     * No Material/Packing mapping is applied here; existing BOM values are
     * only canonicalized and linked to the selected Child Color ids.
     */
    public void applyToBom(BomDocument bom, BomProductColor productColor, ProductColorMaster master) {
        if (bom == null || productColor == null || master == null) return;
        master = ensureLegacyImageFromBom(master, bom, productColor);
        productColor.setProductColorMasterId(master.getId());
        linkBomValuesToMaster(bom, productColor, master);
    }

    private ProductColorMaster candidateFromBom(BomDocument bom, BomProductColor productColor) {
        ProductColorMaster candidate = new ProductColorMaster();
        candidate.setBuyer(trim(bom.getHeader() == null ? null : bom.getHeader().getBuyer()));
        candidate.setSeason(firstNonBlank(productColor.getSeason(), bom.getHeader() == null ? null : bom.getHeader().getSeason()));
        candidate.setPatternNumber(firstNonBlank(productColor.getPatternNumber(), bom.getHeader() == null ? null : bom.getHeader().getPatternNumber()));
        candidate.setStyleNumber(trim(bom.getHeader() == null ? null : bom.getHeader().getStyleNumber()));
        candidate.setStyleName(trim(bom.getHeader() == null ? null : bom.getHeader().getStyleName()));
        candidate.setProductColor(trim(productColor.getColorName()));
        candidate.setMasterKey(keyFor(candidate));
        candidate.setChildColors(childColorsFromBom(bom, productColor));
        candidate.setSourceBomId(bom.getId());
        candidate.setSourceOrderId(bom.getOrderId());
        candidate.setSourceBomNo(trim(bom.getBomNo()));
        candidate.setSourceBomName(trim(bom.getBomName()));
        candidate.setActive(true);
        return candidate;
    }

    private ProductColorMaster upsertCandidate(ProductColorMaster candidate, String preferredMasterId) {
        LocalDateTime now = LocalDateTime.now();

        // A manually selected master remains the parent. It receives any new
        // Child Colors found in the BOM but does not get replaced by a second
        // master simply because the current BOM header differs slightly.
        if (!blank(preferredMasterId)) {
            Optional<ProductColorMaster> preferred = repository.findById(trim(preferredMasterId));
            if (preferred.isPresent()) {
                ProductColorMaster entity = ensureChildColorIds(preferred.get());
                entity.setChildColors(mergeChildColors(entity.getChildColors(), candidate.getChildColors()));
                applySourceBom(entity, candidate);
                entity.setUpdatedAt(now);
                return repository.save(entity);
            }
        }

        Optional<ProductColorMaster> existing = repository.findByMasterKey(candidate.getMasterKey());
        if (existing.isPresent()) {
            ProductColorMaster entity = ensureChildColorIds(existing.get());
            entity.setBuyer(candidate.getBuyer());
            entity.setSeason(candidate.getSeason());
            entity.setPatternNumber(candidate.getPatternNumber());
            entity.setStyleNumber(candidate.getStyleNumber());
            entity.setStyleName(candidate.getStyleName());
            entity.setProductColor(candidate.getProductColor());
            entity.setChildColors(mergeChildColors(entity.getChildColors(), candidate.getChildColors()));
            applySourceBom(entity, candidate);
            entity.setUpdatedAt(now);
            return repository.save(entity);
        }

        candidate.setChildColors(normalizeChildColors(candidate.getChildColors()));
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        return repository.save(candidate);
    }

    private List<ProductColorAttribute> childColorsFromBom(BomDocument bom, BomProductColor productColor) {
        LinkedHashMap<String, ProductColorAttribute> result = new LinkedHashMap<>();
        appendChildColors(result, bom.getCoreLines(), productColor);
        for (BomPacking packing : safe(bom.getPackings())) {
            appendChildColors(result, packing == null ? null : packing.getLines(), productColor);
        }
        return new ArrayList<>(result.values());
    }

    private void appendChildColors(
            Map<String, ProductColorAttribute> result,
            List<BomLine> lines,
            BomProductColor productColor
    ) {
        for (BomLine line : safe(lines)) {
            String value = colorValue(line, productColor);
            if (blank(value)) continue;
            ProductColorAttribute item = new ProductColorAttribute();
            item.setId(UUID.randomUUID().toString());
            item.setChildColor(value);
            result.putIfAbsent(normalize(value), item);
        }
    }

    private void linkBomValuesToMaster(BomDocument bom, BomProductColor productColor, ProductColorMaster master) {
        linkLines(bom.getCoreLines(), productColor, master);
        for (BomPacking packing : safe(bom.getPackings())) {
            if (packing != null) linkLines(packing.getLines(), productColor, master);
        }
    }

    private void linkLines(List<BomLine> lines, BomProductColor productColor, ProductColorMaster master) {
        for (BomLine line : safe(lines)) {
            if (line == null) continue;
            for (BomLineColorValue value : safe(line.getProductColorValues())) {
                if (value == null || !Objects.equals(productColor.getId(), value.getProductColorId())) continue;
                ProductColorAttribute child = findChildColor(master.getId(), value.getChildColorId(), value.getValue());
                if (child == null) continue;
                value.setChildColorId(child.getId());
                value.setValue(trim(child.getChildColor()));
                if (line.getColorValues() != null) {
                    line.getColorValues().put(productColor.getColorName(), trim(child.getChildColor()));
                }
            }
        }
    }

    /**
     * Source BOM means the first BOM that introduced/linked this master.
     * Do not replace it when the same Product Color is reused by another BOM;
     * otherwise the Source BOM link would jump unpredictably between BOMs.
     */
    private void applySourceBom(ProductColorMaster target, ProductColorMaster source) {
        if (target == null || source == null || !blank(target.getSourceBomId())) return;
        target.setSourceBomId(source.getSourceBomId());
        target.setSourceOrderId(source.getSourceOrderId());
        target.setSourceBomNo(source.getSourceBomNo());
        target.setSourceBomName(source.getSourceBomName());
    }

    private String colorValue(BomLine line, BomProductColor productColor) {
        for (BomLineColorValue value : safe(line == null ? null : line.getProductColorValues())) {
            if (value != null && Objects.equals(productColor.getId(), value.getProductColorId()) && !blank(value.getValue())) {
                return trim(value.getValue());
            }
        }
        if (line != null && line.getColorValues() != null) {
            for (Map.Entry<String, String> item : line.getColorValues().entrySet()) {
                if (normalize(item.getKey()).equals(normalize(productColor.getColorName())) && !blank(item.getValue())) {
                    return trim(item.getValue());
                }
            }
        }
        return "";
    }

    /** Backfills a Product Color Master image from older BOM COLOR attachments once. */
    private ProductColorMaster ensureImageFromLegacyBom(ProductColorMaster master) {
        if (master == null || master.hasImage() || blank(master.getSourceBomId())) return master;
        Optional<BomDocument> sourceBom = bomRepository.findById(master.getSourceBomId());
        if (sourceBom.isEmpty()) return master;
        BomProductColor productColor = findMatchingBomProductColor(sourceBom.get(), master, null);
        return ensureLegacyImageFromBom(master, sourceBom.get(), productColor);
    }

    private ProductColorMaster ensureLegacyImageFromBom(
            ProductColorMaster master,
            BomDocument bom,
            BomProductColor preferredProductColor
    ) {
        if (master == null || bom == null || master.hasImage()) return master;
        BomProductColor productColor = findMatchingBomProductColor(bom, master, preferredProductColor);
        BomAttachment attachment = findLegacyColorImage(bom, master, productColor);
        if (attachment == null || blank(attachment.getStoredFileName())) return master;

        try {
            BomFileStorageService.StoredFile copied = fileStorage.copyStoredFile(
                    attachment.getStoredFileName(),
                    firstNonBlank(attachment.getOriginalFileName(), master.getProductColor() + "-product-color-image"),
                    attachment.getContentType(),
                    attachment.getSize()
            );
            master.setImageStoredFileName(copied.storedFileName());
            master.setImageFileName(copied.originalFileName());
            master.setImageContentType(copied.contentType());
            master.setImageSize(copied.size());
            master.setImageUpdatedAt(LocalDateTime.now());
            master.setUpdatedAt(LocalDateTime.now());
            return repository.save(master);
        } catch (RuntimeException ignored) {
            return master;
        }
    }

    private BomProductColor findMatchingBomProductColor(
            BomDocument bom,
            ProductColorMaster master,
            BomProductColor preferredProductColor
    ) {
        if (preferredProductColor != null && productColorMatchesMaster(preferredProductColor, master)) {
            return preferredProductColor;
        }
        for (BomProductColor item : safe(bom == null ? null : bom.getProductColors())) {
            if (item == null) continue;
            if (!blank(master.getId()) && Objects.equals(master.getId(), item.getProductColorMasterId())) return item;
            if (productColorMatchesMaster(item, master)) return item;
        }
        return preferredProductColor;
    }

    private boolean productColorMatchesMaster(BomProductColor productColor, ProductColorMaster master) {
        if (productColor == null || master == null) return false;
        if (!normalize(productColor.getColorName()).equals(normalize(master.getProductColor()))) return false;
        if (!blank(productColor.getSeason()) && !blank(master.getSeason())
                && !normalize(productColor.getSeason()).equals(normalize(master.getSeason()))) return false;
        if (!blank(productColor.getPatternNumber()) && !blank(master.getPatternNumber())
                && !normalize(productColor.getPatternNumber()).equals(normalize(master.getPatternNumber()))) return false;
        return true;
    }

    private BomAttachment findLegacyColorImage(
            BomDocument bom,
            ProductColorMaster master,
            BomProductColor productColor
    ) {
        for (BomAttachment attachment : safe(bom == null ? null : bom.getAttachments())) {
            if (attachment == null || !"COLOR".equalsIgnoreCase(trim(attachment.getScope())) || !isImageAttachment(attachment)) {
                continue;
            }
            String productColorId = trim(productColor == null ? null : productColor.getId());
            if (!productColorId.isBlank() && productColorId.equals(trim(attachment.getProductColorId()))) return attachment;

            String colorKey = normalize(attachment.getColorKey());
            if (!colorKey.isBlank()) {
                if (productColor != null && colorKey.equals(normalize(productColor.getColorName()))) return attachment;
                if (master != null && colorKey.equals(normalize(master.getProductColor()))) return attachment;
            }
        }
        return null;
    }

    private boolean isImageAttachment(BomAttachment attachment) {
        String contentType = trim(attachment == null ? null : attachment.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = trim(attachment == null ? null : attachment.getOriginalFileName()).toLowerCase(Locale.ROOT);
        return contentType.startsWith("image/") || fileName.matches(".*\\.(png|jpe?g|gif|webp|bmp)$");
    }

    /** Backfills source BOM name/order for Product Color documents created before this feature. */
    private ProductColorMaster ensureSourceBomDetails(ProductColorMaster master) {
        if (master == null || blank(master.getSourceBomId())) return master;
        if (!blank(master.getSourceOrderId()) && !blank(master.getSourceBomName())) return master;

        Optional<BomDocument> sourceBom = bomRepository.findById(master.getSourceBomId());
        if (sourceBom.isEmpty()) return master;

        BomDocument bom = sourceBom.get();
        master.setSourceOrderId(bom.getOrderId());
        master.setSourceBomNo(bom.getBomNo());
        master.setSourceBomName(bom.getBomName());
        master.setUpdatedAt(LocalDateTime.now());
        return repository.save(master);
    }

    private ProductColorMaster fromRequest(ProductColorMaster entity, ProductColorMasterRequest request) {
        if (request == null) throw new MasterDataValidationException("Product Color data is required");
        entity.setBuyer(trim(request.buyer()));
        entity.setSeason(required(request.season(), "Season is required"));
        entity.setPatternNumber(required(request.patternNumber(), "Pattern Number is required"));
        entity.setStyleNumber(trim(request.styleNumber()));
        entity.setStyleName(trim(request.styleName()));
        entity.setProductColor(required(request.productColor(), "Product Color is required"));
        entity.setActive(request.active() == null || request.active());
        entity.setChildColors(toChildColors(request.childColors()));
        entity.setMasterKey(keyFor(entity));
        return entity;
    }

    private List<ProductColorAttribute> toChildColors(List<ProductColorAttributeRequest> source) {
        List<ProductColorAttribute> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ProductColorAttributeRequest request : safe(source)) {
            if (request == null || blank(request.childColor())) continue;
            String childColor = trim(request.childColor());
            if (!seen.add(normalize(childColor))) continue;
            ProductColorAttribute item = new ProductColorAttribute();
            item.setId(blank(request.id()) ? UUID.randomUUID().toString() : trim(request.id()));
            item.setChildColor(childColor);
            result.add(item);
        }
        return result;
    }

    private List<ProductColorAttribute> normalizeChildColors(List<ProductColorAttribute> source) {
        List<ProductColorAttribute> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ProductColorAttribute raw : safe(source)) {
            if (raw == null || blank(raw.getChildColor())) continue;
            String childColor = trim(raw.getChildColor());
            if (!seen.add(normalize(childColor))) continue;
            ProductColorAttribute item = new ProductColorAttribute();
            item.setId(blank(raw.getId()) ? UUID.randomUUID().toString() : trim(raw.getId()));
            item.setChildColor(childColor);
            result.add(item);
        }
        return result;
    }

    private List<ProductColorAttribute> mergeChildColors(
            List<ProductColorAttribute> existing,
            List<ProductColorAttribute> incoming
    ) {
        List<ProductColorAttribute> result = normalizeChildColors(existing);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ProductColorAttribute item : result) seen.add(normalize(item.getChildColor()));
        for (ProductColorAttribute item : normalizeChildColors(incoming)) {
            if (seen.add(normalize(item.getChildColor()))) result.add(item);
        }
        return result;
    }

    /** Adds missing ids to older Product Color documents on first use. */
    private ProductColorMaster ensureChildColorIds(ProductColorMaster master) {
        List<ProductColorAttribute> normalized = normalizeChildColors(master.getChildColors());
        boolean changed = normalized.size() != safe(master.getChildColors()).size();
        if (!changed) {
            for (int index = 0; index < normalized.size(); index++) {
                ProductColorAttribute before = safe(master.getChildColors()).get(index);
                ProductColorAttribute after = normalized.get(index);
                if (!Objects.equals(trim(before == null ? null : before.getId()), trim(after.getId()))
                        || !Objects.equals(trim(before == null ? null : before.getChildColor()), trim(after.getChildColor()))) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) return master;
        master.setChildColors(normalized);
        master.setUpdatedAt(LocalDateTime.now());
        return repository.save(master);
    }

    private String keyFor(ProductColorMaster item) {
        return normalize(item.getBuyer()) + "|" + normalize(item.getSeason()) + "|"
                + normalize(item.getPatternNumber()) + "|" + normalize(item.getStyleNumber())
                + "|" + normalize(item.getProductColor());
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MasterDataValidationException("Product Color image is required");
        }

        String contentType = trim(file.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = trim(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean supported = contentType.startsWith("image/")
                || fileName.matches(".*\\.(png|jpe?g|gif|webp|bmp)$");
        if (!supported) {
            throw new MasterDataValidationException("Only image files can be uploaded for Product Color");
        }
    }

    private String required(String value, String message) {
        String clean = trim(value);
        if (clean.isBlank()) throw new MasterDataValidationException(message);
        return clean;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return trim(value);
        return "";
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String normalize(String value) { return trim(value).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    public record ProductColorImageResource(Resource resource, String fileName, String contentType) { }
}
