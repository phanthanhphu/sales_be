package org.bsl.sales.service;

import org.bsl.sales.dto.BomCreateRequest;
import org.bsl.sales.dto.BomLineRequest;
import org.bsl.sales.dto.BomPackingRequest;
import org.bsl.sales.dto.BomProductColorRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.ProductColorAttribute;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.springframework.core.io.Resource;
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
import java.util.UUID;

@Service
public class BomService {
    private final BomDocumentRepository bomRepository;
    private final MprDocumentRepository mprRepository;
    private final OrderService orderService;
    private final BomExcelParser excelParser;
    private final BomFileStorageService fileStorage;
    private final ProductColorMasterService productColorMasterService;

    public BomService(
            BomDocumentRepository bomRepository,
            MprDocumentRepository mprRepository,
            OrderService orderService,
            BomExcelParser excelParser,
            BomFileStorageService fileStorage,
            ProductColorMasterService productColorMasterService
    ) {
        this.bomRepository = bomRepository;
        this.mprRepository = mprRepository;
        this.orderService = orderService;
        this.excelParser = excelParser;
        this.fileStorage = fileStorage;
        this.productColorMasterService = productColorMasterService;
    }

    public List<BomDocument> listByOrder(String orderId) {
        orderService.get(orderId);
        return bomRepository.findByOrderIdOrderByUpdatedAtDesc(orderId).stream()
                .map(this::normalizeProductColorLinks)
                .toList();
    }

    public BomDocument get(String id) {
        return normalizeProductColorLinks(bomRepository.findById(id)
                .orElseThrow(() -> new OrderBomMprNotFoundException("BOM not found")));
    }

    public BomDocument create(String orderId, BomCreateRequest request) {
        orderService.get(orderId);
        LocalDateTime now = LocalDateTime.now();

        BomDocument bom = new BomDocument();
        bom.setOrderId(orderId);
        bom.setBomNo(required(request.bomNo(), "BOM No is required"));
        bom.setBomName(required(request.bomName(), "BOM Name is required"));
        bom.setHeader(request.header() == null ? new org.bsl.sales.model.BomHeader() : request.header());
        bom.setStatus("DRAFT");
        bom.setCreatedAt(now);
        bom.setUpdatedAt(now);
        bom.setCreatedBy(RequestActor.current());
        bom.setUpdatedBy(RequestActor.current());

        BomDocument saved = bomRepository.save(bom);
        orderService.markBomInProgress(orderId);
        return saved;
    }

    public BomDocument update(String id, BomCreateRequest request) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        bom.setBomNo(required(request.bomNo(), "BOM No is required"));
        bom.setBomName(required(request.bomName(), "BOM Name is required"));
        bom.setHeader(request.header() == null ? new org.bsl.sales.model.BomHeader() : request.header());
        return saveChanged(bom);
    }

    public BomDocument upload(String orderId, String bomNo, String bomName, MultipartFile file) {
        orderService.get(orderId);

        BomExcelParser.ParsedBom parsed = excelParser.parse(file);
        BomFileStorageService.StoredFile stored = fileStorage.store(file);
        LocalDateTime now = LocalDateTime.now();

        BomDocument bom = new BomDocument();
        bom.setOrderId(orderId);
        bom.setBomNo(firstNonBlank(bomNo, parsed.header().getStyleNumber(), "BOM-" + now.toString().replace(":", "")));
        bom.setBomName(firstNonBlank(bomName, parsed.header().getStyleName(), file.getOriginalFilename(), "BOM"));
        bom.setStatus("DRAFT");
        bom.setSourceFileName(stored.originalFileName());
        bom.setSourceFileStoredName(stored.storedFileName());
        bom.setCreatedAt(now);
        bom.setUpdatedAt(now);
        bom.setCreatedBy(RequestActor.current());
        bom.setUpdatedBy(RequestActor.current());

        applyParsedBom(bom, parsed);

        BomDocument saved = bomRepository.save(normalizeProductColorLinks(bom));
        // Product Color Master assigns stable childColorId values after the
        // source workbook is parsed. Save once more so the BOM rows retain
        // those links instead of only the readable child-color text.
        productColorMasterService.synchronizeFromBom(saved);
        saved = bomRepository.save(normalizeProductColorLinks(saved));
        orderService.markBomInProgress(orderId);
        return saved;
    }

    public BomDocument replaceExcel(String id, MultipartFile file) {
        BomDocument bom = get(id);
        ensureEditable(bom);

        BomExcelParser.ParsedBom parsed = excelParser.parse(file);
        BomFileStorageService.StoredFile stored = fileStorage.store(file);

        // Replacing the source workbook also replaces all material/packing anchors and color items.
        deleteAllAttachmentFiles(bom);
        fileStorage.deleteQuietly(bom.getSourceFileStoredName());

        bom.setSourceFileName(stored.originalFileName());
        bom.setSourceFileStoredName(stored.storedFileName());
        bom.setAttachments(new ArrayList<>());
        applyParsedBom(bom, parsed);

        return saveChanged(bom);
    }

    public void delete(String id) {
        BomDocument bom = get(id);
        if (isUsedByMpr(bom.getId())) {
            throw new OrderBomMprValidationException("Cannot delete BOM because it is referenced by the order MPR");
        }

        fileStorage.deleteQuietly(bom.getSourceFileStoredName());
        deleteAllAttachmentFiles(bom);
        bomRepository.delete(bom);
    }

    public BomDocument submit(String id) {
        BomDocument bom = get(id);
        // A BOM may validly contain only Packing lines (for example, an imported workbook
        // with no separate Core section). Accept any BOM that has at least one material line.
        boolean hasMaterialLines = !safe(bom.getCoreLines()).isEmpty()
                || safe(bom.getPackings()).stream().anyMatch(packing -> !safe(packing.getLines()).isEmpty());
        if (!hasMaterialLines) {
            throw new OrderBomMprValidationException("Cannot submit BOM without material lines");
        }

        bom.setStatus("SUBMITTED");
        bom.setSubmittedAt(LocalDateTime.now());
        bom.setSubmittedBy(RequestActor.current());
        BomDocument saved = saveChanged(bom);
        orderService.markBomSubmitted(bom.getOrderId());
        return saved;
    }

    /**
     * Adds a Product Color link to a BOM. Product Color Master remains the
     * single source of truth for Product / Style Color, Child Colors and image.
     * Pattern Number and Season still belong to this BOM.
     */
    public BomDocument addProductColor(String bomId, BomProductColorRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        // Product Color must be created (and its image saved) in Product Color Master first.
        // BOM only stores the link and never owns a second product-image file.
        org.bsl.sales.model.ProductColorMaster master = productColorMasterService.resolve(
                required(request.productColorMasterId(), "Product Color Master is required")
        );

        String colorName = required(master.getProductColor(), "Product Color is required");
        if (findProductColorByName(bom, colorName) != null) {
            throw new OrderBomMprValidationException("Product Color already exists in this BOM: " + colorName);
        }

        BomProductColor productColor = new BomProductColor();
        productColor.setId(UUID.randomUUID().toString());
        productColor.setProductColorMasterId(master.getId());
        productColor.setColorName(colorName);
        productColor.setPatternNumber(firstNonBlank(request.patternNumber(), bom.getHeader() == null ? null : bom.getHeader().getPatternNumber()));
        productColor.setSeason(firstNonBlank(request.season(), bom.getHeader() == null ? null : bom.getHeader().getSeason()));
        productColor.setSourceColumnIndex(null);
        ensureProductColorsList(bom).add(productColor);
        productColorMasterService.applyToBom(bom, productColor, master);
        forEachLine(bom, line -> synchronizeLineProductColorValues(bom, line, false));
        syncLegacyColorNames(bom);
        return saveChanged(bom);
    }

    /**
     * Updates product information once. Packing links and material-line color links keep the stable id,
     * therefore their visible Color / Pattern Number / Season immediately reflect this edited item.
     */
    public BomDocument updateProductColor(String bomId, String productColorId, BomProductColorRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomProductColor productColor = resolveProductColor(bom, productColorId, "");
        String oldColorName = productColor.getColorName();
        // Linking an existing BOM color also requires a saved Product Color Master.
        org.bsl.sales.model.ProductColorMaster selectedMaster = productColorMasterService.resolve(
                required(request.productColorMasterId(), "Product Color Master is required")
        );
        String newColorName = required(selectedMaster.getProductColor(), "Product Color is required");
        BomProductColor duplicate = findProductColorByName(bom, newColorName);
        if (duplicate != null && !Objects.equals(duplicate.getId(), productColor.getId())) {
            throw new OrderBomMprValidationException("Product Color already exists in this BOM: " + newColorName);
        }

        productColor.setColorName(newColorName);
        productColor.setProductColorMasterId(selectedMaster.getId());
        productColor.setPatternNumber(firstNonBlank(request.patternNumber(), productColor.getPatternNumber(), bom.getHeader() == null ? null : bom.getHeader().getPatternNumber()));
        productColor.setSeason(firstNonBlank(request.season(), productColor.getSeason(), bom.getHeader() == null ? null : bom.getHeader().getSeason()));
        productColorMasterService.applyToBom(bom, productColor, selectedMaster);
        forEachLine(bom, line -> synchronizeLineProductColorValues(bom, line, false));

        // Keep legacy name-keyed fields in sync without breaking the stable id links.
        final String updatedColorName = newColorName;
        forEachLine(bom, line -> renameLegacyColorKey(line, oldColorName, updatedColorName));
        for (BomAttachment attachment : ensureAttachments(bom)) {
            if ("COLOR".equalsIgnoreCase(attachment.getScope())
                    && (Objects.equals(productColor.getId(), attachment.getProductColorId())
                    || normalize(oldColorName).equals(normalize(attachment.getColorKey())))) {
                attachment.setProductColorId(productColor.getId());
                attachment.setColorKey(newColorName);
            }
        }
        syncPackingColorNames(bom);
        syncLegacyColorNames(bom);
        return saveChanged(bom);
    }

    public BomDocument deleteProductColor(String bomId, String productColorId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomProductColor productColor = resolveProductColor(bom, productColorId, "");
        if (isProductColorLinked(bom, productColor.getId())) {
            throw new OrderBomMprValidationException(
                    "Cannot delete Product Color because it is linked to a Packing or a Material Line. Edit the Product Color instead."
            );
        }

        for (BomAttachment attachment : new ArrayList<>(ensureAttachments(bom))) {
            if (Objects.equals(productColor.getId(), attachment.getProductColorId())) {
                fileStorage.deleteQuietly(attachment.getStoredFileName());
                ensureAttachments(bom).remove(attachment);
            }
        }
        ensureProductColorsList(bom).removeIf(item -> Objects.equals(productColor.getId(), item.getId()));
        syncLegacyColorNames(bom);
        return saveChanged(bom);
    }

    public BomDocument addPacking(String bomId, BomPackingRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = new BomPacking();
        packing.setId(UUID.randomUUID().toString());
        packing.setPackingName(required(request.packingName(), "Packing name is required"));
        packing.setSequence(request.sequence() == null ? safe(bom.getPackings()).size() + 1 : request.sequence());
        applyPackingProductColors(bom, packing, request.applicableProductColorIds(), request.applicableColors());
        ensurePackings(bom).add(packing);
        return saveChanged(bom);
    }

    public BomDocument updatePacking(String bomId, String packingId, BomPackingRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = findPacking(bom, packingId);
        packing.setPackingName(required(request.packingName(), "Packing name is required"));
        if (request.sequence() != null) packing.setSequence(request.sequence());
        applyPackingProductColors(bom, packing, request.applicableProductColorIds(), request.applicableColors());
        return saveChanged(bom);
    }

    public BomDocument deletePacking(String bomId, String packingId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = findPacking(bom, packingId);
        for (BomAttachment attachment : safe(packing.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
        for (BomLine line : safe(packing.getLines())) {
            for (BomAttachment attachment : safe(line.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
            rememberDeletedSourceRow(bom, line.getSourceRowNumber());
        }

        ensurePackings(bom).removeIf(item -> packingId.equals(item.getId()));
        return saveChanged(bom);
    }

    public BomDocument addLine(String bomId, String packingId, BomLineRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomLine line = request.toModel();
        validateManualLine(line);
        synchronizeLineProductColorValues(bom, line, true);
        line.setId(UUID.randomUUID().toString());

        if (packingId == null || packingId.isBlank()) {
            ensureCoreLines(bom).add(line);
        } else {
            ensureLines(findPacking(bom, packingId)).add(line);
        }

        syncLegacyColorNames(bom);
        return saveChanged(bom);
    }

    public BomDocument updateLine(String bomId, String lineId, BomLineRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomLine existing = findLine(bom, lineId);
        BomLine next = request.toModel();
        validateManualLine(next);
        synchronizeLineProductColorValues(bom, next, true);
        copyLine(existing, next);
        syncLegacyColorNames(bom);
        return saveChanged(bom);
    }

    public BomDocument deleteLine(String bomId, String lineId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomLine line = findLine(bom, lineId);
        for (BomAttachment attachment : safe(line.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
        rememberDeletedSourceRow(bom, line.getSourceRowNumber());

        boolean removed = ensureCoreLines(bom).removeIf(item -> lineId.equals(item.getId()));
        for (BomPacking packing : ensurePackings(bom)) {
            removed |= ensureLines(packing).removeIf(item -> lineId.equals(item.getId()));
        }
        if (!removed) throw new OrderBomMprNotFoundException("BOM line not found");
        return saveChanged(bom);
    }

    public BomDocument addAttachment(
            String bomId,
            String scope,
            String productColorId,
            String colorKey,
            String packingId,
            String lineId,
            MultipartFile file
    ) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        String normalizedScope = normalizeScope(scope);
        if ("PACKING".equals(normalizedScope)) {
            throw new OrderBomMprValidationException(
                    "Packing file upload is not supported. Attach a file to a Material Line instead."
            );
        }
        if ("COLOR".equals(normalizedScope)) {
            throw new OrderBomMprValidationException(
                    "Product Color images must be uploaded in Product Color Master. BOM only uses the saved Product Color image through its master link."
            );
        }
        String normalizedProductColorId = trim(productColorId);
        String normalizedColorKey = trim(colorKey);

        BomFileStorageService.StoredFile stored = fileStorage.store(file);
        BomAttachment attachment = newAttachment(
                stored,
                normalizedScope,
                normalizedProductColorId,
                normalizedColorKey,
                packingId,
                lineId,
                null,
                false
        );

        attachToScope(bom, attachment);
        return saveChanged(bom);
    }

    public BomDocument deleteAttachment(String bomId, String attachmentId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomAttachment attachment = findAttachment(bom, attachmentId);
        fileStorage.deleteQuietly(attachment.getStoredFileName());
        removeAttachmentFromAllScopes(bom, attachmentId);
        return saveChanged(bom);
    }

    public AttachmentResource downloadAttachment(String bomId, String attachmentId) {
        BomDocument bom = get(bomId);
        BomAttachment attachment = findAttachment(bom, attachmentId);
        Resource resource = fileStorage.load(attachment.getStoredFileName());
        return new AttachmentResource(resource, attachment.getOriginalFileName(), attachment.getContentType());
    }

    private void applyParsedBom(BomDocument bom, BomExcelParser.ParsedBom parsed) {
        bom.setHeader(parsed.header());
        // Replace means the current workbook is the source of truth: keep only its Product Color items.
        bom.setColors(new ArrayList<>());
        bom.setProductColors(new ArrayList<>(safe(parsed.productColors())));
        bom.setCoreLines(new ArrayList<>(safe(parsed.coreLines())));
        bom.setPackings(new ArrayList<>(safe(parsed.packings())));
        bom.setDeletedSourceRows(new ArrayList<>());
        normalizeProductColorLinks(bom);

        for (BomExcelParser.ParsedAttachment imported : safe(parsed.importedAttachments())) {
            BomFileStorageService.StoredFile stored = fileStorage.storeBytes(
                    imported.bytes(),
                    imported.originalFileName(),
                    imported.contentType()
            );

            BomAttachment attachment = newAttachment(
                    stored,
                    imported.scope(),
                    imported.productColorId(),
                    imported.colorKey(),
                    imported.packingId(),
                    imported.lineId(),
                    imported.sourceRowNumber(),
                    true
            );
            attachToScope(bom, attachment);
        }
    }

    private BomAttachment newAttachment(
            BomFileStorageService.StoredFile stored,
            String scope,
            String productColorId,
            String colorKey,
            String packingId,
            String lineId,
            Integer sourceRowNumber,
            boolean importedFromExcel
    ) {
        BomAttachment attachment = new BomAttachment();
        attachment.setId(UUID.randomUUID().toString());
        attachment.setOriginalFileName(stored.originalFileName());
        attachment.setStoredFileName(stored.storedFileName());
        attachment.setContentType(stored.contentType());
        attachment.setSize(stored.size());
        attachment.setScope(scope);
        attachment.setProductColorId(trim(productColorId));
        attachment.setColorKey(trim(colorKey));
        attachment.setPackingId(trim(packingId));
        attachment.setLineId(trim(lineId));
        attachment.setSourceRowNumber(sourceRowNumber);
        attachment.setImportedFromExcel(importedFromExcel);
        attachment.setDownloadUrl("/api/boms/" + "{bomId}" + "/attachments/" + attachment.getId() + "/download");
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setUploadedBy(importedFromExcel ? "EXCEL_IMPORT" : RequestActor.current());
        return attachment;
    }

    private void attachToScope(BomDocument bom, BomAttachment attachment) {
        String scope = normalizeScope(attachment.getScope());
        attachment.setScope(scope);
        attachment.setDownloadUrl("/api/boms/" + bom.getId() + "/attachments/" + attachment.getId() + "/download");

        switch (scope) {
            case "PACKING" -> ensureAttachments(findPacking(bom, required(attachment.getPackingId(), "Packing is required"))).add(attachment);
            case "LINE" -> ensureAttachments(findLine(bom, required(attachment.getLineId(), "BOM line is required"))).add(attachment);
            case "COLOR" -> {
                BomProductColor productColor = resolveProductColor(bom, attachment.getProductColorId(), attachment.getColorKey());
                attachment.setProductColorId(productColor.getId());
                attachment.setColorKey(productColor.getColorName());

                // A Product Color has one product-image slot. Uploading a new image replaces the old one.
                if (isImageAttachment(attachment)) {
                    removeExistingProductColorImages(bom, productColor.getId());
                }
                ensureAttachments(bom).add(attachment);
            }
            default -> ensureAttachments(bom).add(attachment);
        }
    }

    private BomDocument saveChanged(BomDocument bom) {
        normalizeProductColorLinks(bom);
        bom.setUpdatedAt(LocalDateTime.now());
        bom.setUpdatedBy(RequestActor.current());
        BomDocument saved = bomRepository.save(bom);
        // Master synchronization may assign Product Color Master ids and
        // Child Color ids to material rows, therefore persist that link.
        productColorMasterService.synchronizeFromBom(saved);
        return bomRepository.save(normalizeProductColorLinks(saved));
    }

    private void ensureEditable(BomDocument bom) {
        if ("SUBMITTED".equalsIgnoreCase(bom.getStatus()) && !RequestActor.isAdmin()) {
            throw new OrderBomMprValidationException("Submitted BOM can only be edited by Admin");
        }
    }

    private boolean isUsedByMpr(String bomId) {
        return mprRepository.findAll().stream().anyMatch(mpr -> safe(mpr.getSelections()).stream()
                .anyMatch(selection -> bomId.equals(selection.getBomId())));
    }

    private BomPacking findPacking(BomDocument bom, String packingId) {
        if (packingId == null || packingId.isBlank()) {
            throw new OrderBomMprValidationException("Packing id is required");
        }
        return ensurePackings(bom).stream()
                .filter(item -> packingId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("Packing not found"));
    }

    private BomLine findLine(BomDocument bom, String lineId) {
        for (BomLine line : ensureCoreLines(bom)) {
            if (lineId.equals(line.getId())) return line;
        }
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomLine line : ensureLines(packing)) {
                if (lineId.equals(line.getId())) return line;
            }
        }
        throw new OrderBomMprNotFoundException("BOM line not found");
    }

    private BomAttachment findAttachment(BomDocument bom, String attachmentId) {
        for (BomAttachment attachment : ensureAttachments(bom)) {
            if (attachmentId.equals(attachment.getId())) return attachment;
        }
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomAttachment attachment : ensureAttachments(packing)) {
                if (attachmentId.equals(attachment.getId())) return attachment;
            }
            for (BomLine line : ensureLines(packing)) {
                for (BomAttachment attachment : ensureAttachments(line)) {
                    if (attachmentId.equals(attachment.getId())) return attachment;
                }
            }
        }
        for (BomLine line : ensureCoreLines(bom)) {
            for (BomAttachment attachment : ensureAttachments(line)) {
                if (attachmentId.equals(attachment.getId())) return attachment;
            }
        }
        throw new OrderBomMprNotFoundException("Attachment not found");
    }

    private void removeAttachmentFromAllScopes(BomDocument bom, String attachmentId) {
        ensureAttachments(bom).removeIf(item -> attachmentId.equals(item.getId()));
        for (BomPacking packing : ensurePackings(bom)) {
            ensureAttachments(packing).removeIf(item -> attachmentId.equals(item.getId()));
            for (BomLine line : ensureLines(packing)) {
                ensureAttachments(line).removeIf(item -> attachmentId.equals(item.getId()));
            }
        }
        for (BomLine line : ensureCoreLines(bom)) {
            ensureAttachments(line).removeIf(item -> attachmentId.equals(item.getId()));
        }
    }

    private void removeExistingProductColorImages(BomDocument bom, String productColorId) {
        List<BomAttachment> rootAttachments = ensureAttachments(bom);
        List<BomAttachment> existing = rootAttachments.stream()
                .filter(item -> "COLOR".equalsIgnoreCase(item.getScope()))
                .filter(item -> Objects.equals(productColorId, item.getProductColorId()))
                .filter(this::isImageAttachment)
                .toList();
        for (BomAttachment attachment : existing) {
            fileStorage.deleteQuietly(attachment.getStoredFileName());
        }
        rootAttachments.removeIf(existing::contains);
    }

    private void deleteAllAttachmentFiles(BomDocument bom) {
        for (BomAttachment attachment : ensureAttachments(bom)) fileStorage.deleteQuietly(attachment.getStoredFileName());
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomAttachment attachment : ensureAttachments(packing)) fileStorage.deleteQuietly(attachment.getStoredFileName());
            for (BomLine line : ensureLines(packing)) {
                for (BomAttachment attachment : ensureAttachments(line)) fileStorage.deleteQuietly(attachment.getStoredFileName());
            }
        }
        for (BomLine line : ensureCoreLines(bom)) {
            for (BomAttachment attachment : ensureAttachments(line)) fileStorage.deleteQuietly(attachment.getStoredFileName());
        }
    }

    private void rememberDeletedSourceRow(BomDocument bom, Integer sourceRowNumber) {
        if (sourceRowNumber == null) return;
        if (bom.getDeletedSourceRows() == null) bom.setDeletedSourceRows(new ArrayList<>());
        if (!bom.getDeletedSourceRows().contains(sourceRowNumber)) bom.getDeletedSourceRows().add(sourceRowNumber);
    }

    /** Populates productColors for legacy BOMs and synchronizes legacy colors/values for API compatibility. */
    private BomDocument normalizeProductColorLinks(BomDocument bom) {
        ensureProductColors(bom);
        for (BomLine line : ensureCoreLines(bom)) synchronizeLineProductColorValues(bom, line, false);
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomLine line : ensureLines(packing)) synchronizeLineProductColorValues(bom, line, false);
        }
        syncPackingColorNames(bom);
        syncLegacyColorNames(bom);
        return bom;
    }

    private void synchronizeLineProductColorValues(BomDocument bom, BomLine line, boolean strict) {
        ensureProductColors(bom);
        List<BomLineColorValue> linkedValues = line.getProductColorValues() == null ? new ArrayList<>() : line.getProductColorValues();
        Map<String, String> legacyValues = line.getColorValues() == null ? new LinkedHashMap<>() : line.getColorValues();

        LinkedHashMap<String, String> linkedByColorName = new LinkedHashMap<>();
        List<BomLineColorValue> cleanLinkedValues = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();

        for (BomLineColorValue item : linkedValues) {
            if (item == null || trim(item.getProductColorId()).isBlank()) continue;
            String rawValue = trim(item.getValue());
            String childColorId = trim(item.getChildColorId());
            if (rawValue.isBlank() && childColorId.isBlank()) continue;

            BomProductColor productColor = findProductColorById(bom, item.getProductColorId());
            if (productColor == null) productColor = findProductColorByName(bom, item.getProductColorId());
            if (productColor == null) {
                if (strict) throw new OrderBomMprValidationException("Selected Product Color does not belong to this BOM");
                continue;
            }
            if (!seenIds.add(productColor.getId())) {
                if (strict) throw new OrderBomMprValidationException("A Product Color can only be selected once in the same material line");
                continue;
            }

            // A Product Color Master stores only Child Colors. For a manual
            // BOM line, require one of those Child Colors; imports without a
            // linked master keep their readable value until synchronization.
            if (!trim(productColor.getProductColorMasterId()).isBlank()) {
                ProductColorAttribute child = productColorMasterService.findChildColor(
                        productColor.getProductColorMasterId(), childColorId, rawValue
                );
                if (child == null && strict) {
                    throw new OrderBomMprValidationException(
                            "Select a Child Color belonging to the selected Product / Style Color"
                    );
                }
                if (child != null) {
                    childColorId = trim(child.getId());
                    rawValue = trim(child.getChildColor());
                }
            }

            if (rawValue.isBlank()) {
                if (strict) throw new OrderBomMprValidationException("Child Color is required");
                continue;
            }

            BomLineColorValue clean = new BomLineColorValue();
            clean.setProductColorId(productColor.getId());
            clean.setChildColorId(childColorId);
            clean.setValue(rawValue);
            cleanLinkedValues.add(clean);
            linkedByColorName.put(productColor.getColorName(), rawValue);
        }

        // Existing APIs may still send the old { BLACK: '...' } map. Turn it
        // into linked items and attach a Child Color id whenever a master is
        // already available.
        if (cleanLinkedValues.isEmpty() && !legacyValues.isEmpty()) {
            for (Map.Entry<String, String> entry : legacyValues.entrySet()) {
                String colorName = trim(entry.getKey());
                String value = trim(entry.getValue());
                if (colorName.isBlank() || value.isBlank()) continue;
                BomProductColor productColor = findProductColorByName(bom, colorName);
                if (productColor == null) productColor = addLegacyProductColor(bom, colorName);
                if (!seenIds.add(productColor.getId())) continue;

                String childColorId = "";
                if (!trim(productColor.getProductColorMasterId()).isBlank()) {
                    ProductColorAttribute child = productColorMasterService.findChildColor(
                            productColor.getProductColorMasterId(), "", value
                    );
                    if (child != null) {
                        childColorId = trim(child.getId());
                        value = trim(child.getChildColor());
                    }
                }

                BomLineColorValue clean = new BomLineColorValue();
                clean.setProductColorId(productColor.getId());
                clean.setChildColorId(childColorId);
                clean.setValue(value);
                cleanLinkedValues.add(clean);
                linkedByColorName.put(productColor.getColorName(), value);
            }
        }

        line.setProductColorValues(cleanLinkedValues);
        line.setColorValues(linkedByColorName);
    }

    private void ensureProductColors(BomDocument bom) {
        if (bom.getProductColors() == null) bom.setProductColors(new ArrayList<>());
        for (String colorName : safe(bom.getColors())) {
            if (findProductColorByName(bom, colorName) == null && !trim(colorName).isBlank()) {
                addLegacyProductColor(bom, colorName);
            }
        }

        // Old records may only contain line.colorValues; create linked items from those values once.
        for (BomLine line : ensureCoreLines(bom)) ensureProductColorsFromLegacyValues(bom, line);
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomLine line : ensureLines(packing)) ensureProductColorsFromLegacyValues(bom, line);
        }
    }

    private void ensureProductColorsFromLegacyValues(BomDocument bom, BomLine line) {
        if (line.getColorValues() == null) return;
        for (String colorName : line.getColorValues().keySet()) {
            if (findProductColorByName(bom, colorName) == null && !trim(colorName).isBlank()) {
                addLegacyProductColor(bom, colorName);
            }
        }
    }

    private BomProductColor addLegacyProductColor(BomDocument bom, String colorName) {
        BomProductColor productColor = new BomProductColor();
        productColor.setId(UUID.randomUUID().toString());
        productColor.setColorName(trim(colorName));
        productColor.setPatternNumber(bom.getHeader() == null ? "" : trim(bom.getHeader().getPatternNumber()));
        productColor.setSeason(bom.getHeader() == null ? "" : trim(bom.getHeader().getSeason()));
        productColor.setSourceColumnIndex(null);
        ensureProductColorsList(bom).add(productColor);
        return productColor;
    }

    private List<BomProductColor> ensureProductColorsList(BomDocument bom) {
        if (bom.getProductColors() == null) bom.setProductColors(new ArrayList<>());
        return bom.getProductColors();
    }

    private void syncLegacyColorNames(BomDocument bom) {
        List<String> colorNames = new ArrayList<>();
        for (BomProductColor productColor : ensureProductColorsList(bom)) {
            String name = trim(productColor.getColorName());
            if (!name.isBlank() && colorNames.stream().noneMatch(item -> normalize(item).equals(normalize(name)))) {
                colorNames.add(name);
            }
        }
        bom.setColors(colorNames);
    }

    private BomProductColor resolveProductColor(BomDocument bom, String productColorId, String colorKey) {
        BomProductColor byId = findProductColorById(bom, productColorId);
        if (byId != null) return byId;
        // Allows old FE clients which used the color name itself as the selected value.
        BomProductColor byProductColorName = findProductColorByName(bom, productColorId);
        if (byProductColorName != null) return byProductColorName;
        BomProductColor byName = findProductColorByName(bom, colorKey);
        if (byName != null) return byName;
        throw new OrderBomMprValidationException("Selected Product Color does not belong to this BOM");
    }

    private BomProductColor findProductColorById(BomDocument bom, String productColorId) {
        String wanted = trim(productColorId);
        if (wanted.isBlank()) return null;
        return ensureProductColorsList(bom).stream()
                .filter(item -> item != null && wanted.equals(item.getId()))
                .findFirst()
                .orElse(null);
    }

    private BomProductColor findProductColorByName(BomDocument bom, String colorName) {
        String wanted = normalize(colorName);
        if (wanted.isBlank()) return null;
        return ensureProductColorsList(bom).stream()
                .filter(item -> item != null && wanted.equals(normalize(item.getColorName())))
                .findFirst()
                .orElse(null);
    }

    /** Applies packing links by stable Product Color id and mirrors readable names for legacy clients. */
    private void applyPackingProductColors(
            BomDocument bom,
            BomPacking packing,
            List<String> requestedIds,
            List<String> requestedNames
    ) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String requested : safe(requestedIds)) {
            if (trim(requested).isBlank()) continue;
            BomProductColor productColor = resolveProductColor(bom, requested, "");
            ids.add(productColor.getId());
        }
        // Backward compatibility with clients which still send color names.
        if (ids.isEmpty()) {
            for (String requested : safe(requestedNames)) {
                if (trim(requested).isBlank()) continue;
                ids.add(resolveProductColor(bom, requested, requested).getId());
            }
        }
        packing.setApplicableProductColorIds(new ArrayList<>(ids));
        syncPackingColorNames(bom, packing);
    }

    /** Rebuilds legacy readable names from the Product Color IDs, so edits automatically propagate to Packing. */
    private void syncPackingColorNames(BomDocument bom) {
        for (BomPacking packing : ensurePackings(bom)) syncPackingColorNames(bom, packing);
    }

    private void syncPackingColorNames(BomDocument bom, BomPacking packing) {
        List<String> ids = packing.getApplicableProductColorIds() == null ? new ArrayList<>() : packing.getApplicableProductColorIds();
        // Migrate old name-based records into stable ids once.
        if (ids.isEmpty() && packing.getApplicableColors() != null && !packing.getApplicableColors().isEmpty()) {
            LinkedHashSet<String> migrated = new LinkedHashSet<>();
            for (String colorName : packing.getApplicableColors()) {
                BomProductColor productColor = findProductColorByName(bom, colorName);
                if (productColor != null) migrated.add(productColor.getId());
            }
            ids = new ArrayList<>(migrated);
            packing.setApplicableProductColorIds(ids);
        }

        List<String> names = new ArrayList<>();
        for (String id : ids) {
            BomProductColor productColor = findProductColorById(bom, id);
            if (productColor != null && !trim(productColor.getColorName()).isBlank()) {
                names.add(productColor.getColorName().trim());
            }
        }
        packing.setApplicableColors(names);
    }

    private boolean isProductColorLinked(BomDocument bom, String productColorId) {
        for (BomPacking packing : ensurePackings(bom)) {
            if (safe(packing.getApplicableProductColorIds()).contains(productColorId)) return true;
        }
        final boolean[] linked = { false };
        forEachLine(bom, line -> {
            if (safe(line.getProductColorValues()).stream()
                    .anyMatch(value -> value != null && Objects.equals(productColorId, value.getProductColorId()))) {
                linked[0] = true;
            }
        });
        return linked[0];
    }

    private void forEachLine(BomDocument bom, java.util.function.Consumer<BomLine> action) {
        for (BomLine line : ensureCoreLines(bom)) action.accept(line);
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomLine line : ensureLines(packing)) action.accept(line);
        }
    }

    private void renameLegacyColorKey(BomLine line, String oldColorName, String newColorName) {
        if (line.getColorValues() == null || normalize(oldColorName).equals(normalize(newColorName))) return;
        String matchingKey = line.getColorValues().keySet().stream()
                .filter(key -> normalize(key).equals(normalize(oldColorName)))
                .findFirst()
                .orElse(null);
        if (matchingKey == null) return;
        String value = line.getColorValues().remove(matchingKey);
        line.getColorValues().putIfAbsent(newColorName, value);
    }

    private void copyLine(BomLine target, BomLine source) {
        target.setMaterialGroupNo(source.getMaterialGroupNo());
        target.setMaterialType(source.getMaterialType());
        target.setSapCode(source.getSapCode());
        target.setDetailNo(source.getDetailNo());
        target.setPosition(source.getPosition());
        target.setPositionDescription(source.getPositionDescription());
        target.setPositionDescriptionExtra(source.getPositionDescriptionExtra());
        target.setPieceCode(source.getPieceCode());
        target.setDimensionX(source.getDimensionX());
        target.setDimensionY(source.getDimensionY());
        target.setQuantity(source.getQuantity());
        target.setDirection(source.getDirection());
        target.setCosting(source.getCosting());
        target.setCostingUnit(source.getCostingUnit());
        target.setConsumptionNet(source.getConsumptionNet());
        target.setConsumptionUnit(source.getConsumptionUnit());
        target.setBomRemark(source.getBomRemark());
        target.setProductColorValues(copyProductColorValues(source.getProductColorValues()));
        target.setColorValues(source.getColorValues() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getColorValues()));
        target.setDetailLine(source.isDetailLine());
    }

    private List<BomLineColorValue> copyProductColorValues(List<BomLineColorValue> source) {
        List<BomLineColorValue> result = new ArrayList<>();
        for (BomLineColorValue item : safe(source)) {
            BomLineColorValue copy = new BomLineColorValue();
            copy.setProductColorId(item.getProductColorId());
            copy.setChildColorId(item.getChildColorId());
            copy.setValue(item.getValue());
            result.add(copy);
        }
        return result;
    }

    private void validateManualLine(BomLine line) {
        boolean detail = line.isDetailLine();
        if (!detail && trim(line.getMaterialType()).isBlank()) {
            throw new OrderBomMprValidationException("Material type is required for a material-group line");
        }
    }

    private String normalizeScope(String value) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "COLOR", "PACKING", "LINE" -> normalized;
            default -> "BOM";
        };
    }

    private boolean isImageAttachment(BomAttachment attachment) {
        String contentType = trim(attachment.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = trim(attachment.getOriginalFileName()).toLowerCase(Locale.ROOT);
        return contentType.startsWith("image/") || fileName.matches(".*\\.(png|jpe?g|gif|webp|bmp)$");
    }

    private String required(String value, String message) {
        String clean = trim(value);
        if (clean.isBlank()) throw new OrderBomMprValidationException(message);
        return clean;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = trim(value);
            if (!clean.isBlank()) return clean;
        }
        return "";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        return trim(value).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private List<BomPacking> ensurePackings(BomDocument bom) {
        if (bom.getPackings() == null) bom.setPackings(new ArrayList<>());
        return bom.getPackings();
    }

    private List<BomLine> ensureCoreLines(BomDocument bom) {
        if (bom.getCoreLines() == null) bom.setCoreLines(new ArrayList<>());
        return bom.getCoreLines();
    }

    private List<BomLine> ensureLines(BomPacking packing) {
        if (packing.getLines() == null) packing.setLines(new ArrayList<>());
        return packing.getLines();
    }

    private List<BomAttachment> ensureAttachments(BomDocument bom) {
        if (bom.getAttachments() == null) bom.setAttachments(new ArrayList<>());
        return bom.getAttachments();
    }

    private List<BomAttachment> ensureAttachments(BomPacking packing) {
        if (packing.getAttachments() == null) packing.setAttachments(new ArrayList<>());
        return packing.getAttachments();
    }

    private List<BomAttachment> ensureAttachments(BomLine line) {
        if (line.getAttachments() == null) line.setAttachments(new ArrayList<>());
        return line.getAttachments();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record AttachmentResource(Resource resource, String fileName, String contentType) { }
}
