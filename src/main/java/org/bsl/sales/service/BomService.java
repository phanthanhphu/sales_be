package org.bsl.sales.service;

import org.bsl.sales.dto.BomCreateRequest;
import org.bsl.sales.dto.BomLineRequest;
import org.bsl.sales.dto.BomLinePageResponse;
import org.bsl.sales.dto.BomPackingRequest;
import org.bsl.sales.dto.BomProductColorRequest;
import org.bsl.sales.dto.ProductColorAttributeRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomLineDocument;
import org.bsl.sales.model.BomImage;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.ProductColorAttribute;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.BomMprSourceRevision;
import org.bsl.sales.support.MasterDataSequentialKey;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.text.Normalizer;
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
    private static final String BOM_NO_PREFIX = "B";
    private static final String LEGACY_BOM_NO_PREFIX = "BOM";
    private static final String BOM_SEQUENCE_PREFIX = "bom_order:";
    private static final int BOM_NO_MINIMUM_DIGITS = 4;

    private final BomDocumentRepository bomRepository;
    private final MprDocumentRepository mprRepository;
    private final OrderService orderService;
    private final BomExcelParser excelParser;
    private final BomFileStorageService fileStorage;
    private final BomImageStorageService imageStorage;
    private final BomLineStore lineStore;
    private final BomProductColorLegacyMigrationService productColorLegacyMigration;
    private final MasterDataSequenceService sequenceService;

    public BomService(
            BomDocumentRepository bomRepository,
            MprDocumentRepository mprRepository,
            OrderService orderService,
            BomExcelParser excelParser,
            BomFileStorageService fileStorage,
            BomImageStorageService imageStorage,
            BomLineStore lineStore,
            BomProductColorLegacyMigrationService productColorLegacyMigration,
            MasterDataSequenceService sequenceService
    ) {
        this.bomRepository = bomRepository;
        this.mprRepository = mprRepository;
        this.orderService = orderService;
        this.excelParser = excelParser;
        this.fileStorage = fileStorage;
        this.imageStorage = imageStorage;
        this.lineStore = lineStore;
        this.productColorLegacyMigration = productColorLegacyMigration;
        this.sequenceService = sequenceService;
    }

    public List<BomDocument> listByOrder(String orderId) {
        SalesOrder order = orderService.get(orderId);
        return bomRepository.findByOrderIdOrderByCreatedAtDescUpdatedAtDesc(orderId).stream()
                .map(bom -> prepareStoredSummary(bom, order))
                .toList();
    }

    /** Full aggregate for internal MPR/export workflows. */
    public BomDocument get(String id) {
        BomDocument stored = findStored(id);
        SalesOrder order = orderService.get(stored.getOrderId());
        stored = prepareStoredSummary(stored, order);
        return normalizeProductColorLinks(lineStore.hydrate(stored));
    }

    /** Lightweight header returned to the BOM screen. Material rows use the paged lines endpoint. */
    public BomDocument getSummary(String id) {
        BomDocument stored = findStored(id);
        SalesOrder order = orderService.get(stored.getOrderId());
        return prepareStoredSummary(stored, order);
    }

    public BomDocument get(String id, String expectedBuyerKey) {
        BomDocument bom = get(id);
        validateExpectedBuyer(bom, expectedBuyerKey);
        return bom;
    }

    public BomDocument getSummary(String id, String expectedBuyerKey) {
        BomDocument bom = getSummary(id);
        validateExpectedBuyer(bom, expectedBuyerKey);
        return bom;
    }

    private void validateExpectedBuyer(BomDocument bom, String expectedBuyerKey) {
        if (expectedBuyerKey == null || expectedBuyerKey.isBlank()) return;
        String expected = BuyerKeys.normalize(expectedBuyerKey);
        if (!expected.equals(BuyerKeys.legacyDefault(bom.getBuyerKey()))) {
            throw new OrderBomMprNotFoundException("BOM not found for Buyer " + expected);
        }
    }

    private BomDocument findStored(String id) {
        return bomRepository.findById(id)
                .orElseThrow(() -> new OrderBomMprNotFoundException("BOM not found"));
    }

    private BomDocument prepareStoredSummary(BomDocument bom, SalesOrder order) {
        if (bom.getBuyerKey() == null || bom.getBuyerKey().isBlank()) {
            bom.setBuyerKey(BuyerKeys.legacyDefault(order.getBuyerKey()));
        }
        bom.setOrderMprCompleted(SalesOrder.STATUS_MPR_COMPLETED.equals(order.getStatus()));
        bom.setUsedInMpr(isUsedByMpr(bom.getId()));
        if (productColorLegacyMigration.migrateIfNeeded(bom)) {
            // Structural migration only: visible BOM data stays the same, so do not
            // increment mprSourceRevision or mark active MPRs stale.
            bom = bomRepository.save(bom);
        }
        if (!lineStore.isSeparate(bom)) {
            // One-time, backward-compatible migration of embedded rows and old line-image attachments.
            normalizeProductColorLinks(bom);
            migrateLegacyLineImages(bom);
            lineStore.replaceAll(bom);
            lineStore.compactForStorage(bom);
            bom = bomRepository.save(bom);
        } else {
            lineStore.compactForStorage(bom);
        }
        return bom;
    }

    public BomDocument create(String orderId, BomCreateRequest request) {
        SalesOrder order = orderService.get(orderId);
        requireMprNotCompleted(order);
        LocalDateTime now = LocalDateTime.now();

        BomDocument bom = new BomDocument();
        bom.setId(UUID.randomUUID().toString());
        bom.setOrderId(orderId);
        bom.setBuyerKey(BuyerKeys.legacyDefault(order.getBuyerKey()));
        bom.setBomName(required(request.bomName(), "BOM Name is required"));
        String generatedBomNo = nextAvailableBomNo(orderId);
        bom.setBomNo(generatedBomNo);
        bom.setBomNoKey(normalize(generatedBomNo));
        bom.setHeader(request.header() == null ? new org.bsl.sales.model.BomHeader() : request.header());
        bom.setStatus("DRAFT");
        bom.setCreatedAt(now);
        bom.setUpdatedAt(now);
        bom.setCreatedBy(RequestActor.current());
        bom.setUpdatedBy(RequestActor.current());
        bom.setLineStorageMode(BomLineStore.SEPARATE);
        bom.setLineCount(0);
        bom.setCoreLineCount(0);
        bom.setImageCount(0);
        bom.setMprSourceRevision(0L);

        BomDocument saved = bomRepository.save(bom);
        orderService.markBomInProgress(orderId);
        return saved;
    }

    public BomDocument update(String id, BomCreateRequest request) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        // BOM No is immutable. Never trust or apply a value sent by the client.
        // Assign a generated number only for a malformed legacy record that has none.
        if (normalize(bom.getBomNo()).isBlank()) {
            String generatedBomNo = nextAvailableBomNo(bom.getOrderId());
            bom.setBomNo(generatedBomNo);
        }
        bom.setBomNoKey(normalize(bom.getBomNo()));
        bom.setBomName(required(request.bomName(), "BOM Name is required"));
        bom.setHeader(request.header() == null ? new org.bsl.sales.model.BomHeader() : request.header());
        return saveMprSourceChanged(bom, "BOM header updated");
    }

    public BomDocument replaceExcel(String id, MultipartFile file) {
        return replaceExcel(id, file, false);
    }

    public BomDocument replaceExcel(
            String id,
            MultipartFile file,
            boolean keepFirstDuplicateProductColors
    ) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        requireLlBeanImplementation(bom.getBuyerKey(), "BOM Excel replacement");

        BomExcelParser.ParsedBom parsed = excelParser.parse(file, keepFirstDuplicateProductColors);
        validateParsedBom(parsed);
        BomFileStorageService.StoredFile stored = fileStorage.store(file);

        String oldSource = bom.getSourceFileStoredName();
        List<String> oldAttachmentFiles = collectAttachmentStoredNames(bom);
        List<BomImage> oldImages = collectLineImages(bom);

        bom.setSourceFileName(stored.originalFileName());
        bom.setSourceFileStoredName(stored.storedFileName());
        bom.setAttachments(new ArrayList<>());
        try {
            applyParsedBom(bom, parsed);
            BomDocument saved = saveMprSourceChanged(bom, "BOM Excel source replaced");
            fileStorage.deleteQuietly(oldSource);
            oldAttachmentFiles.forEach(fileStorage::deleteQuietly);
            oldImages.forEach(imageStorage::delete);
            return saved;
        } catch (RuntimeException ex) {
            fileStorage.deleteQuietly(stored.storedFileName());
            deleteAllAttachmentFiles(bom);
            throw ex;
        }
    }

    public void delete(String id) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        if (isUsedByMpr(bom.getId())) {
            throw new OrderBomMprValidationException("Cannot delete BOM because it is referenced by the order MPR");
        }

        fileStorage.deleteQuietly(bom.getSourceFileStoredName());
        deleteAllAttachmentFiles(bom);
        lineStore.deleteByBomId(bom.getId());
        bomRepository.delete(bom);
    }

    public BomDocument submit(String id) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        if ("SUBMITTED".equalsIgnoreCase(bom.getStatus())) {
            throw new OrderBomMprValidationException(
                    "BOM is already submitted. Use Resubmit to return it to DRAFT before submitting it again."
            );
        }
        validateBomAggregate(bom, true);
        bom.setStatus("SUBMITTED");
        bom.setSubmittedAt(LocalDateTime.now());
        bom.setSubmittedBy(RequestActor.current());
        BomDocument saved = saveChanged(bom);
        orderService.markBomSubmitted(bom.getOrderId());
        return saved;
    }

    /**
     * Returns a submitted-but-unused BOM to DRAFT so BOM can revise it and submit
     * it again. Once Sales has generated a persisted MPR batch from this BOM, the
     * BOM cannot be pulled back by Resubmit; Sales must remove that MPR batch first.
     */
    public BomDocument resubmit(String id) {
        BomDocument bom = get(id);
        ensureEditable(bom);
        if (!"SUBMITTED".equalsIgnoreCase(bom.getStatus())) {
            throw new OrderBomMprValidationException("Only a submitted BOM can be resubmitted");
        }
        if (isUsedByMpr(bom.getId())) {
            throw new OrderBomMprValidationException(
                    "This BOM cannot be resubmitted because it has already been used in the order's MPR. "
                            + "Delete its MPR generation batch first if you need to return the BOM to DRAFT."
            );
        }

        bom.setStatus("DRAFT");
        bom.setSubmittedAt(null);
        bom.setSubmittedBy(null);
        BomDocument saved = saveChanged(bom);
        orderService.markBomReturnedToDraft(bom.getOrderId());
        return saved;
    }


    /** Adds a Product Color owned only by this BOM. */
    public BomDocument addProductColor(String bomId, BomProductColorRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        String colorName = required(request.colorName(), "Product Color is required");
        String patternNumber = required(request.patternNumber(), "Pattern Number is required");
        String season = required(request.season(), "Season is required");
        String styleNumber = required(request.styleNumber(), "Style Number is required");
        if (findProductColorByIdentity(bom, patternNumber, colorName, season, styleNumber) != null) {
            throw duplicateProductColorException(colorName, patternNumber, season, styleNumber);
        }

        BomProductColor productColor = new BomProductColor();
        productColor.setId(UUID.randomUUID().toString());
        productColor.setProductColorMasterId(null);
        productColor.setColorName(colorName);
        productColor.setPatternNumber(patternNumber);
        productColor.setSeason(season);
        productColor.setStyleNumber(styleNumber);
        int sequence = request.sequence() == null ? nextProductColorSequence(bom) : request.sequence();
        validateProductColorSequence(bom, null, sequence);
        productColor.setSequence(sequence);
        productColor.setSourceColumnIndex(null);
        applyRequestedChildColors(bom, productColor, request.childColors());
        ensureProductColorsList(bom).add(productColor);
        syncLegacyColorNames(bom);
        return saveMprSourceChanged(bom, "Product Color added");
    }

    /**
     * Updates the BOM-local Product Color. Material-line links keep the stable
     * Product Color/Child Color ids, so renamed Child Colors flow into the BOM
     * lines and later MPR updates without touching any other BOM.
     */
    public BomDocument updateProductColor(String bomId, String productColorId, BomProductColorRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomProductColor productColor = resolveProductColor(bom, productColorId, "");
        String beforeSource = productColorSourceFingerprint(productColor);
        String oldColorName = productColor.getColorName();
        String newColorName = required(request.colorName(), "Product Color is required");
        String newPatternNumber = required(request.patternNumber(), "Pattern Number is required");
        String newSeason = required(request.season(), "Season is required");
        String newStyleNumber = required(request.styleNumber(), "Style Number is required");

        BomProductColor duplicate = findProductColorByIdentity(
                bom, newPatternNumber, newColorName, newSeason, newStyleNumber
        );
        if (duplicate != null && !Objects.equals(duplicate.getId(), productColor.getId())) {
            throw duplicateProductColorException(newColorName, newPatternNumber, newSeason, newStyleNumber);
        }

        productColor.setColorName(newColorName);
        productColor.setProductColorMasterId(null);
        productColor.setPatternNumber(newPatternNumber);
        productColor.setSeason(newSeason);
        productColor.setStyleNumber(newStyleNumber);
        if (request.sequence() != null) {
            validateProductColorSequence(bom, productColor.getId(), request.sequence());
            productColor.setSequence(request.sequence());
        }
        applyRequestedChildColors(bom, productColor, request.childColors());
        forEachLine(bom, line -> synchronizeLineProductColorValues(bom, line, false));

        // Keep legacy name-keyed fields and Product Color image metadata in sync.
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
        syncLegacyColorNames(bom);
        return Objects.equals(beforeSource, productColorSourceFingerprint(productColor))
                ? saveChanged(bom)
                : saveMprSourceChanged(bom, "Product Color updated");
    }

    public BomDocument deleteProductColor(String bomId, String productColorId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomProductColor productColor = resolveProductColor(bom, productColorId, "");
        if (isProductColorLinked(bom, productColor.getId())) {
            throw new OrderBomMprValidationException(
                    "Cannot delete Product Color because it is used by a Material Line. Remove the Product Color Value from that line first."
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
        return saveMprSourceChanged(bom, "Product Color deleted");
    }

    public BomDocument addPacking(String bomId, BomPackingRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = new BomPacking();
        packing.setId(UUID.randomUUID().toString());
        String packingName = required(request.packingName(), "Packing name is required");
        int sequence = nextPackingSequence(bom);
        validatePackingIdentity(bom, null, packingName, sequence);
        packing.setPackingName(packingName);
        packing.setSequence(sequence);
        // Packing/Product Color applicability is no longer managed at Packing level.
        // Product Color assignment belongs to each BOM Line via Product Color Values.
        packing.setApplicableProductColorIds(new ArrayList<>());
        packing.setApplicableColors(new ArrayList<>());
        ensurePackings(bom).add(packing);
        return saveMprSourceChanged(bom, "Packing added");
    }

    public BomDocument updatePacking(String bomId, String packingId, BomPackingRequest request) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = findPacking(bom, packingId);
        String packingName = required(request.packingName(), "Packing name is required");
        int sequence = packing.getSequence() == null ? nextPackingSequence(bom) : packing.getSequence();
        validatePackingIdentity(bom, packingId, packingName, sequence);
        packing.setPackingName(packingName);
        packing.setSequence(sequence);
        // Keep legacy fields empty. Product Color assignment is managed on BOM Lines.
        packing.setApplicableProductColorIds(new ArrayList<>());
        packing.setApplicableColors(new ArrayList<>());
        return saveMprSourceChanged(bom, "Packing updated");
    }

    public BomDocument deletePacking(String bomId, String packingId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomPacking packing = findPacking(bom, packingId);
        for (BomAttachment attachment : safe(packing.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
        for (BomLine line : safe(packing.getLines())) {
            for (BomAttachment attachment : safe(line.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
            imageStorage.delete(line.getPrimaryImage());
            rememberDeletedSourceRow(bom, line.getSourceRowNumber());
        }

        ensurePackings(bom).removeIf(item -> packingId.equals(item.getId()));
        return saveMprSourceChanged(bom, "Packing deleted");
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
        return saveMprSourceChanged(bom, "BOM material line added");
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
        return saveMprSourceChanged(bom, "BOM material line updated");
    }

    public BomDocument deleteLine(String bomId, String lineId) {
        BomDocument bom = get(bomId);
        ensureEditable(bom);

        BomLine line = findLine(bom, lineId);
        for (BomAttachment attachment : safe(line.getAttachments())) fileStorage.deleteQuietly(attachment.getStoredFileName());
        imageStorage.delete(line.getPrimaryImage());
        rememberDeletedSourceRow(bom, line.getSourceRowNumber());

        boolean removed = ensureCoreLines(bom).removeIf(item -> lineId.equals(item.getId()));
        for (BomPacking packing : ensurePackings(bom)) {
            removed |= ensureLines(packing).removeIf(item -> lineId.equals(item.getId()));
        }
        if (!removed) throw new OrderBomMprNotFoundException("BOM line not found");
        return saveMprSourceChanged(bom, "BOM material line deleted");
    }

    public BomLinePageResponse getLines(String bomId, String packingId, int page, int size) {
        BomDocument bom = getSummary(bomId);
        if (packingId != null && !packingId.isBlank() && !"__CORE__".equalsIgnoreCase(packingId)) {
            findPacking(bom, packingId);
        }
        return lineStore.page(bomId, packingId, page, size);
    }

    public BomLine uploadLineImage(String bomId, String lineId, MultipartFile file) {
        BomDocument bom = getSummary(bomId);
        ensureEditable(bom);
        BomLineDocument document = lineStore.findDocument(bomId, lineId);
        BomLine line = document.getLine();
        BomImage next = imageStorage.store(file, false, line.getSourceRowNumber(), 2);
        BomImage previous = line.getPrimaryImage();
        line.setPrimaryImage(next);
        try {
            BomLine saved = lineStore.saveDocument(document);
            if (previous == null) bom.setImageCount(bom.getImageCount() + 1);
            touchStoredSummary(bom);
            imageStorage.delete(previous);
            return saved;
        } catch (RuntimeException ex) {
            imageStorage.delete(next);
            throw ex;
        }
    }

    public BomLine deleteLineImage(String bomId, String lineId) {
        BomDocument bom = getSummary(bomId);
        ensureEditable(bom);
        BomLineDocument document = lineStore.findDocument(bomId, lineId);
        BomLine line = document.getLine();
        BomImage previous = line.getPrimaryImage();
        line.setPrimaryImage(null);
        BomLine saved = lineStore.saveDocument(document);
        if (previous != null) {
            bom.setImageCount(Math.max(0, bom.getImageCount() - 1));
            touchStoredSummary(bom);
            imageStorage.delete(previous);
        }
        return saved;
    }

    public LineImageResource downloadLineImage(String bomId, String lineId, String variant) {
        getSummary(bomId);
        BomLineDocument document = lineStore.findDocument(bomId, lineId);
        BomLine line = document.getLine();
        BomImage image = line.getPrimaryImage();

        // Old EMF/WMF records may not have derivatives because LibreOffice was unavailable during import.
        // Retry lazily and persist the generated PNG metadata so subsequent GET requests are fast.
        if (image != null && !"original".equalsIgnoreCase(trim(variant)) && imageStorage.ensureDerivatives(image)) {
            lineStore.saveDocument(document);
        }

        return new LineImageResource(
                imageStorage.load(image, variant),
                imageStorage.fileName(image, variant),
                imageStorage.contentType(image, variant)
        );
    }

    private void touchStoredSummary(BomDocument bom) {
        bom.setUpdatedAt(LocalDateTime.now());
        bom.setUpdatedBy(RequestActor.current());
        lineStore.compactForStorage(bom);
        bomRepository.save(bom);
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
            if (!isImage(file.getOriginalFilename(), file.getContentType())) {
                throw new OrderBomMprValidationException("Product Color upload supports one image file only");
            }
            BomProductColor productColor = resolveProductColor(bom, productColorId, colorKey);
            productColorId = productColor.getId();
            colorKey = productColor.getColorName();
        }
        if ("BOM".equals(normalizedScope)
                && !isImage(file.getOriginalFilename(), file.getContentType())) {
            throw new OrderBomMprValidationException("Whole BOM upload supports one image file only");
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

    private void migrateLegacyLineImages(BomDocument bom) {
        forEachLine(bom, line -> {
            if (line.getPrimaryImage() != null) return;
            BomAttachment legacy = safe(line.getAttachments()).stream()
                    .filter(this::isImageAttachment)
                    .findFirst().orElse(null);
            if (legacy == null || !hasText(legacy.getStoredFileName())) return;
            try (var input = fileStorage.load(legacy.getStoredFileName()).getInputStream()) {
                BomImage image = imageStorage.storeBytes(
                        input.readAllBytes(), legacy.getOriginalFileName(), legacy.getContentType(),
                        legacy.isImportedFromExcel(), legacy.getSourceRowNumber(), 2
                );
                imageStorage.bindUrls(image, bom.getId(), line.getId());
                line.setPrimaryImage(image);
                ensureAttachments(line).remove(legacy);
                fileStorage.deleteQuietly(legacy.getStoredFileName());
            } catch (Exception ignored) {
                // Keep the legacy attachment when it cannot be converted safely.
            }
        });
    }

    private void applyParsedBom(BomDocument bom, BomExcelParser.ParsedBom parsed) {
        validateParsedBom(parsed);
        bom.setHeader(parsed.header());
        // Replace means the current workbook is the source of truth: keep only its Product Color items.
        bom.setColors(new ArrayList<>());
        bom.setProductColors(new ArrayList<>(safe(parsed.productColors())));
        bom.setCoreLines(new ArrayList<>(safe(parsed.coreLines())));
        bom.setPackings(new ArrayList<>(safe(parsed.packings())));
        bom.setDeletedSourceRows(new ArrayList<>());
        bom.setIgnoredProductColorSourceColumns(new ArrayList<>(safe(parsed.ignoredProductColorSourceColumns())));
        normalizeProductColorLinks(bom);

        for (BomExcelParser.ParsedAttachment imported : safe(parsed.importedAttachments())) {
            boolean lineImage = "LINE".equalsIgnoreCase(imported.scope())
                    && imported.lineId() != null && !imported.lineId().isBlank()
                    && isImage(imported.originalFileName(), imported.contentType());

            if (lineImage) {
                BomLine line = findLine(bom, imported.lineId());
                BomImage previous = line.getPrimaryImage();
                BomImage image = imageStorage.storeBytes(
                        imported.bytes(), imported.originalFileName(), imported.contentType(),
                        true, imported.sourceRowNumber(), 2
                );
                imageStorage.bindUrls(image, bom.getId(), line.getId());
                line.setPrimaryImage(image);
                if (previous != null) imageStorage.delete(previous);
                continue;
            }

            BomFileStorageService.StoredFile stored = fileStorage.storeBytes(
                    imported.bytes(), imported.originalFileName(), imported.contentType()
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
            case "BOM" -> {
                // The BOM header owns one Whole BOM image. A new upload replaces every previous BOM-level image.
                if (isImageAttachment(attachment)) {
                    removeExistingWholeBomImages(bom);
                }
                ensureAttachments(bom).add(attachment);
            }
            default -> ensureAttachments(bom).add(attachment);
        }
    }

    private BomDocument saveMprSourceChanged(BomDocument bom, String summary) {
        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        BomMprSourceRevision.markChanged(bom, summary, actor, now);
        bom.setUpdatedAt(now);
        bom.setUpdatedBy(actor);
        return persistAggregate(bom);
    }

    private BomDocument saveChanged(BomDocument bom) {
        bom.setUpdatedAt(LocalDateTime.now());
        bom.setUpdatedBy(RequestActor.current());
        return persistAggregate(bom);
    }

    /**
     * Persists material rows before compacting the BOM header. MongoDB therefore never receives
     * one oversized document containing every material line or any image bytes.
     */
    private BomDocument persistAggregate(BomDocument bom) {
        // Last-line write guard: no mutation path is allowed to persist two
        // Product Color records with the same four business keys.
        validateProductColorBusinessUniqueness(ensureProductColorsList(bom));
        normalizeProductColorLinks(bom);
        lineStore.replaceAll(bom);
        lineStore.compactForStorage(bom);
        return bomRepository.save(bom);
    }

    private void ensureEditable(BomDocument bom) {
        // Mutation permissions are enforced by SecurityConfig (ADMIN / BOM / SALES).
        // A submitted BOM is intentionally still editable: relevant source changes
        // increment mprSourceRevision and every active MPR that used the older
        // revision is marked for an explicit Update from BOM.
        if (bom == null) {
            throw new OrderBomMprNotFoundException("BOM not found");
        }
        // Once the order's MPR is COMPLETED, its BOM is a frozen source and every
        // mutation is locked until the MPR is reopened.
        if (bom.isOrderMprCompleted()) {
            throw new OrderBomMprValidationException(
                    "BOM is locked because the MPR for this order has been completed. Reopen the MPR to make changes."
            );
        }
    }

    /** Same lock as {@link #ensureEditable}, used where no BomDocument exists yet (e.g. create). */
    private void requireMprNotCompleted(SalesOrder order) {
        if (order != null && SalesOrder.STATUS_MPR_COMPLETED.equals(order.getStatus())) {
            throw new OrderBomMprValidationException(
                    "BOM is locked because the MPR for this order has been completed. Reopen the MPR to make changes."
            );
        }
    }

    /**
     * A BOM is considered used when a saved MPR generation batch/selection references
     * it. This remains correct even when duplicate consolidation makes another BOM's
     * line the visible survivor, because the generation selection still records the
     * BOM that Sales actually added to MPR.
     */
    private boolean isUsedByMpr(String bomId) {
        return mprRepository.existsBySelectionsBomId(bomId);
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

    private void removeExistingWholeBomImages(BomDocument bom) {
        List<BomAttachment> rootAttachments = ensureAttachments(bom);
        List<BomAttachment> existing = rootAttachments.stream()
                .filter(item -> "BOM".equalsIgnoreCase(item.getScope()) || trim(item.getScope()).isBlank())
                .filter(this::isImageAttachment)
                .toList();
        for (BomAttachment attachment : existing) {
            fileStorage.deleteQuietly(attachment.getStoredFileName());
        }
        rootAttachments.removeIf(existing::contains);
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
                imageStorage.delete(line.getPrimaryImage());
            }
        }
        for (BomLine line : ensureCoreLines(bom)) {
            for (BomAttachment attachment : ensureAttachments(line)) fileStorage.deleteQuietly(attachment.getStoredFileName());
            imageStorage.delete(line.getPrimaryImage());
        }
    }

    private void rememberDeletedSourceRow(BomDocument bom, Integer sourceRowNumber) {
        if (sourceRowNumber == null) return;
        if (bom.getDeletedSourceRows() == null) bom.setDeletedSourceRows(new ArrayList<>());
        if (!bom.getDeletedSourceRows().contains(sourceRowNumber)) bom.getDeletedSourceRows().add(sourceRowNumber);
    }

    /** Populates BOM-local Product Colors/Child Colors and synchronizes legacy colors/values for API compatibility. */
    private BomDocument normalizeProductColorLinks(BomDocument bom) {
        ensureProductColors(bom);
        mergeDuplicateProductColors(bom);
        for (BomLine line : ensureCoreLines(bom)) synchronizeLineProductColorValues(bom, line, false);
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomLine line : ensureLines(packing)) synchronizeLineProductColorValues(bom, line, false);
        }
        syncLegacyColorNames(bom);
        return bom;
    }

    /**
     * Legacy compatibility only: keeps the first Product Color for an exact
     * four-key identity match and redirects old saved links to it.
     *
     * Current Add/Edit/Excel-import flows reject new duplicates before save;
     * this repair path remains only so historical BOMs are not made unusable.
     */
    private void mergeDuplicateProductColors(BomDocument bom) {
        List<BomProductColor> source = ensureProductColorsList(bom);
        if (source.size() < 2) return;

        LinkedHashMap<String, BomProductColor> canonicalByIdentity = new LinkedHashMap<>();
        LinkedHashMap<String, String> replacementIds = new LinkedHashMap<>();
        List<BomProductColor> unique = new ArrayList<>();

        for (BomProductColor item : source) {
            if (item == null) continue;
            if (trim(item.getId()).isBlank()) item.setId(UUID.randomUUID().toString());

            String identity = productColorIdentityKey(item);
            BomProductColor canonical = canonicalByIdentity.get(identity);
            if (canonical == null) {
                canonicalByIdentity.put(identity, item);
                unique.add(item);
                continue;
            }

            replacementIds.put(item.getId(), canonical.getId());
            mergeChildColorDefinitions(canonical, item);
            canonical.setProductColorMasterId(null);
            if (canonical.getSequence() == null && item.getSequence() != null) {
                canonical.setSequence(item.getSequence());
            }
        }

        if (replacementIds.isEmpty()) return;
        bom.setProductColors(unique);

        forEachLine(bom, line -> {
            for (BomLineColorValue value : safe(line.getProductColorValues())) {
                String replacement = replacementIds.get(value.getProductColorId());
                if (replacement != null) value.setProductColorId(replacement);
            }
        });

        for (BomPacking packing : ensurePackings(bom)) {
            LinkedHashSet<String> remapped = new LinkedHashSet<>();
            for (String id : safe(packing.getApplicableProductColorIds())) {
                remapped.add(replacementIds.getOrDefault(id, id));
            }
            packing.setApplicableProductColorIds(new ArrayList<>(remapped));
        }

        for (BomAttachment attachment : ensureAttachments(bom)) {
            String replacement = replacementIds.get(attachment.getProductColorId());
            if (replacement == null) continue;
            attachment.setProductColorId(replacement);
            BomProductColor canonical = findProductColorById(bom, replacement);
            if (canonical != null) attachment.setColorKey(canonical.getColorName());
        }
    }

    private String productColorSourceFingerprint(BomProductColor item) {
        if (item == null) return "";
        StringBuilder value = new StringBuilder(productColorIdentityKey(item))
                .append('\u001F').append(item.getSequence() == null ? "" : item.getSequence());
        for (ProductColorAttribute child : ensureLocalChildColors(item)) {
            if (child == null) continue;
            value.append('\u001E')
                    .append(trim(child.getId()))
                    .append('\u001F')
                    .append(normalize(child.getChildColor()));
        }
        return value.toString();
    }

    private String productColorIdentityKey(BomProductColor item) {
        return productColorIdentityKey(
                item == null ? null : item.getColorName(),
                item == null ? null : item.getPatternNumber(),
                item == null ? null : item.getSeason(),
                item == null ? null : item.getStyleNumber()
        );
    }

    /**
     * Business uniqueness for one BOM Product Color.
     *
     * productColorId remains the technical identity used by FE/BE references.
     * These four fields only define whether two Product Color records are a
     * duplicate from the user's business point of view.
     */
    private String productColorIdentityKey(
            String colorName,
            String patternNumber,
            String season,
            String styleNumber
    ) {
        return String.join("\u001F",
                normalizeProductColorIdentityPart(colorName),
                normalizeProductColorIdentityPart(patternNumber),
                normalizeProductColorIdentityPart(season),
                normalizeProductColorIdentityPart(styleNumber)
        );
    }

    private String normalizeProductColorIdentityPart(String value) {
        String normalized = Normalizer.normalize(trim(value), Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String productColorIdentityDisplay(
            String colorName,
            String patternNumber,
            String season,
            String styleNumber
    ) {
        return String.join(" · ",
                trim(colorName),
                trim(patternNumber),
                trim(season),
                trim(styleNumber)
        );
    }

    private OrderBomMprValidationException duplicateProductColorException(
            String colorName,
            String patternNumber,
            String season,
            String styleNumber
    ) {
        return new OrderBomMprValidationException(
                "Duplicate Product Color detected. This BOM already contains the same "
                        + "Product / Style Color + Pattern Number + Season + Style Number: "
                        + productColorIdentityDisplay(colorName, patternNumber, season, styleNumber)
        );
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

            ProductColorAttribute child = findLocalChildColor(productColor, childColorId, rawValue);
            if (child == null && !strict && !rawValue.isBlank()) {
                child = addLocalChildColor(productColor, childColorId, rawValue);
            }
            if (child == null && strict) {
                throw new OrderBomMprValidationException(
                        "Select a Child Color belonging to Product / Style Color " + productColor.getColorName()
                );
            }
            if (child != null) {
                childColorId = trim(child.getId());
                rawValue = trim(child.getChildColor());
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
        // into BOM-local linked items and create missing Child Colors only for
        // import/legacy normalization (strict=false).
        if (cleanLinkedValues.isEmpty() && !legacyValues.isEmpty()) {
            for (Map.Entry<String, String> entry : legacyValues.entrySet()) {
                String colorName = trim(entry.getKey());
                String value = trim(entry.getValue());
                if (colorName.isBlank() || value.isBlank()) continue;
                BomProductColor productColor = findProductColorByName(bom, colorName);
                if (productColor == null) productColor = addLegacyProductColor(bom, colorName);
                if (!seenIds.add(productColor.getId())) continue;

                ProductColorAttribute child = findLocalChildColor(productColor, "", value);
                if (child == null && !strict) child = addLocalChildColor(productColor, "", value);
                if (child == null && strict) {
                    throw new OrderBomMprValidationException(
                            "Select a Child Color belonging to Product / Style Color " + productColor.getColorName()
                    );
                }

                BomLineColorValue clean = new BomLineColorValue();
                clean.setProductColorId(productColor.getId());
                clean.setChildColorId(child == null ? "" : trim(child.getId()));
                clean.setValue(child == null ? value : trim(child.getChildColor()));
                cleanLinkedValues.add(clean);
                linkedByColorName.put(productColor.getColorName(), clean.getValue());
            }
        }

        line.setProductColorValues(cleanLinkedValues);
        line.setColorValues(linkedByColorName);
    }


    private void applyRequestedChildColors(
            BomDocument bom,
            BomProductColor productColor,
            List<ProductColorAttributeRequest> requests
    ) {
        List<ProductColorAttribute> existing = new ArrayList<>(ensureLocalChildColors(productColor));
        Map<String, ProductColorAttribute> existingById = new LinkedHashMap<>();
        for (ProductColorAttribute item : existing) {
            if (item != null && !trim(item.getId()).isBlank()) existingById.put(trim(item.getId()), item);
        }

        List<ProductColorAttribute> next = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ProductColorAttributeRequest request : safe(requests)) {
            if (request == null) continue;
            String childColor = trim(request.childColor());
            if (childColor.isBlank()) continue;
            String normalizedName = normalize(childColor);
            if (!names.add(normalizedName)) {
                throw new OrderBomMprValidationException("Duplicate Child Color in this Product Color: " + childColor);
            }

            String requestedId = trim(request.id());
            String id = !requestedId.isBlank() && existingById.containsKey(requestedId)
                    ? requestedId : UUID.randomUUID().toString();
            if (!ids.add(id)) throw new OrderBomMprValidationException("Duplicate Child Color id");

            ProductColorAttribute child = new ProductColorAttribute();
            child.setId(id);
            child.setChildColor(childColor);
            next.add(child);
        }

        LinkedHashSet<String> retainedIds = new LinkedHashSet<>();
        for (ProductColorAttribute child : next) retainedIds.add(trim(child.getId()));
        for (ProductColorAttribute old : existing) {
            String oldId = trim(old == null ? null : old.getId());
            if (oldId.isBlank() || retainedIds.contains(oldId)) continue;
            if (isChildColorUsed(bom, productColor.getId(), oldId, old == null ? "" : old.getChildColor())) {
                throw new OrderBomMprValidationException(
                        "Cannot delete Child Color " + trim(old == null ? null : old.getChildColor())
                                + " because it is used by a BOM material line"
                );
            }
        }
        productColor.setChildColors(next);
    }

    private boolean isChildColorUsed(
            BomDocument bom,
            String productColorId,
            String childColorId,
            String childColorText
    ) {
        final boolean[] used = { false };
        forEachLine(bom, line -> {
            if (used[0]) return;
            for (BomLineColorValue value : safe(line.getProductColorValues())) {
                if (value == null || !Objects.equals(productColorId, value.getProductColorId())) continue;
                if (!trim(childColorId).isBlank() && Objects.equals(trim(childColorId), trim(value.getChildColorId()))) {
                    used[0] = true;
                    return;
                }
                if (trim(value.getChildColorId()).isBlank()
                        && !trim(childColorText).isBlank()
                        && normalize(childColorText).equals(normalize(value.getValue()))) {
                    used[0] = true;
                    return;
                }
            }
        });
        return used[0];
    }

    private ProductColorAttribute findLocalChildColor(BomProductColor productColor, String childColorId, String value) {
        String wantedId = trim(childColorId);
        String wantedValue = normalize(value);
        for (ProductColorAttribute child : ensureLocalChildColors(productColor)) {
            if (child == null) continue;
            if (!wantedId.isBlank() && wantedId.equals(trim(child.getId()))) return child;
            if (!wantedValue.isBlank() && wantedValue.equals(normalize(child.getChildColor()))) return child;
        }
        return null;
    }

    private ProductColorAttribute addLocalChildColor(BomProductColor productColor, String preferredId, String value) {
        String text = trim(value);
        if (text.isBlank()) return null;
        ProductColorAttribute existing = findLocalChildColor(productColor, preferredId, text);
        if (existing != null) return existing;

        ProductColorAttribute child = new ProductColorAttribute();
        final String candidateId = trim(preferredId);
        boolean duplicateId = !candidateId.isBlank() && ensureLocalChildColors(productColor).stream()
                .filter(Objects::nonNull)
                .anyMatch(item -> candidateId.equals(trim(item.getId())));
        String finalId = candidateId;
        if (finalId.isBlank() || duplicateId) {
            finalId = UUID.randomUUID().toString();
        }
        child.setId(finalId);
        child.setChildColor(text);
        ensureLocalChildColors(productColor).add(child);
        return child;
    }

    private List<ProductColorAttribute> ensureLocalChildColors(BomProductColor productColor) {
        if (productColor.getChildColors() == null) productColor.setChildColors(new ArrayList<>());
        return productColor.getChildColors();
    }

    private void mergeChildColorDefinitions(BomProductColor target, BomProductColor source) {
        LinkedHashMap<String, ProductColorAttribute> byText = new LinkedHashMap<>();
        for (ProductColorAttribute child : ensureLocalChildColors(target)) {
            if (child == null || trim(child.getChildColor()).isBlank()) continue;
            if (trim(child.getId()).isBlank()) child.setId(UUID.randomUUID().toString());
            byText.putIfAbsent(normalize(child.getChildColor()), child);
        }
        for (ProductColorAttribute child : source == null ? List.<ProductColorAttribute>of() : safe(source.getChildColors())) {
            if (child == null || trim(child.getChildColor()).isBlank()) continue;
            if (byText.containsKey(normalize(child.getChildColor()))) continue;
            ProductColorAttribute copy = new ProductColorAttribute();
            copy.setId(trim(child.getId()).isBlank() ? UUID.randomUUID().toString() : trim(child.getId()));
            copy.setChildColor(trim(child.getChildColor()));
            ensureLocalChildColors(target).add(copy);
            byText.put(normalize(copy.getChildColor()), copy);
        }
    }

    private void validateLocalChildColors(BomProductColor productColor) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ProductColorAttribute child : ensureLocalChildColors(productColor)) {
            if (child == null) throw new OrderBomMprValidationException("Child Color contains an empty item");
            String text = required(child.getChildColor(), "Child Color is required");
            if (trim(child.getId()).isBlank()) child.setId(UUID.randomUUID().toString());
            if (!ids.add(trim(child.getId()))) throw new OrderBomMprValidationException("Duplicate Child Color id");
            if (!names.add(normalize(text))) throw new OrderBomMprValidationException("Duplicate Child Color: " + text);
            limitText(text, 300, "Child Color");
        }
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
        productColor.setStyleNumber(bom.getHeader() == null ? "" : trim(bom.getHeader().getStyleNumber()));
        productColor.setSequence(nextProductColorSequence(bom));
        productColor.setSourceColumnIndex(null);
        ensureProductColorsList(bom).add(productColor);
        return productColor;
    }

    private List<BomProductColor> ensureProductColorsList(BomDocument bom) {
        if (bom.getProductColors() == null) bom.setProductColors(new ArrayList<>());
        return bom.getProductColors();
    }

    private int nextProductColorSequence(BomDocument bom) {
        return ensureProductColorsList(bom).stream()
                .filter(Objects::nonNull)
                .map(BomProductColor::getSequence)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;
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

    private BomProductColor findProductColorByIdentity(
            BomDocument bom,
            String patternNumber,
            String colorName,
            String season,
            String styleNumber
    ) {
        String wanted = productColorIdentityKey(colorName, patternNumber, season, styleNumber);
        if (normalizeProductColorIdentityPart(colorName).isBlank()) return null;
        return ensureProductColorsList(bom).stream()
                .filter(Objects::nonNull)
                .filter(item -> wanted.equals(productColorIdentityKey(item)))
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
        // Packing-level applicability was removed. A Product Color is considered in use
        // only when a BOM material line references it through Product Color Values.
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
        target.setDetailConsumption(source.getDetailConsumption());
        target.setConsumptionNet(source.getConsumptionNet());
        target.setConsumptionUnit(source.getConsumptionUnit());
        target.setBomRemark(source.getBomRemark());
        target.setAdditionalRemark(source.getAdditionalRemark());
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

    private String nextAvailableBomNo(String orderId) {
        String sequenceName = BOM_SEQUENCE_PREFIX + orderId;
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = sequenceService.next(
                    sequenceName,
                    BOM_NO_PREFIX,
                    BOM_NO_MINIMUM_DIGITS,
                    () -> maxExistingBomSequence(orderId)
            );
            if (!bomRepository.existsByOrderIdAndBomNoKey(orderId, normalize(candidate))) {
                return candidate;
            }
        }
        throw new OrderBomMprValidationException("Unable to allocate the next BOM No. Please try again.");
    }

    private long maxExistingBomSequence(String orderId) {
        List<String> existingBomNos = bomRepository.findByOrderIdOrderByCreatedAtDescUpdatedAtDesc(orderId).stream()
                .map(BomDocument::getBomNo)
                .toList();

        long currentMax = MasterDataSequentialKey.maxNumber(existingBomNos, BOM_NO_PREFIX);
        long legacyMax = MasterDataSequentialKey.maxNumber(existingBomNos, LEGACY_BOM_NO_PREFIX);
        return Math.max(currentMax, legacyMax);
    }

    private void validateParsedBom(BomExcelParser.ParsedBom parsed) {
        if (parsed == null) throw new OrderBomMprValidationException("Unable to read BOM Excel file");
        if (parsed.header() == null) throw new OrderBomMprValidationException("BOM header is missing");
        if (safe(parsed.productColors()).isEmpty()) {
            throw new OrderBomMprValidationException("BOM Excel must contain at least one Product Color");
        }
        validateProductColors(parsed.productColors());
        validatePackingCollection(parsed.packings(), false);
        for (BomLine line : safe(parsed.coreLines())) validateManualLine(line);
        for (BomPacking packing : safe(parsed.packings())) {
            for (BomLine line : safe(packing.getLines())) validateManualLine(line);
        }
    }

    private void validateBomAggregate(BomDocument bom, boolean submitting) {
        required(bom.getBomNo(), "BOM No is required");
        required(bom.getBomName(), "BOM Name is required");
        validateProductColors(ensureProductColorsList(bom));
        validatePackingCollection(ensurePackings(bom), submitting);

        int totalLines = 0;
        for (BomLine line : ensureCoreLines(bom)) {
            validateManualLine(line);
            totalLines++;
        }
        for (BomPacking packing : ensurePackings(bom)) {
            List<BomLine> lines = ensureLines(packing);
            if (submitting && lines.isEmpty()) {
                throw new OrderBomMprValidationException("Packing " + packing.getPackingName() + " must contain at least one material line before submit");
            }
            for (BomLine line : lines) {
                validateManualLine(line);
                totalLines++;
            }
        }
        if (submitting && totalLines == 0) {
            throw new OrderBomMprValidationException("BOM must contain at least one material line before submit");
        }
    }


    private void validateProductColorBusinessUniqueness(List<BomProductColor> productColors) {
        LinkedHashMap<String, BomProductColor> byBusinessIdentity = new LinkedHashMap<>();
        for (BomProductColor color : safe(productColors)) {
            if (color == null) continue;
            String identity = productColorIdentityKey(color);
            BomProductColor existing = byBusinessIdentity.putIfAbsent(identity, color);
            if (existing != null) {
                throw duplicateProductColorException(
                        color.getColorName(),
                        color.getPatternNumber(),
                        color.getSeason(),
                        color.getStyleNumber()
                );
            }
        }
    }


    private void validateProductColors(List<BomProductColor> productColors) {
        validateProductColorBusinessUniqueness(productColors);

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<Integer> sequences = new LinkedHashSet<>();

        for (BomProductColor color : safe(productColors)) {
            if (color == null) throw new OrderBomMprValidationException("Product Color contains an empty item");
            String name = required(color.getColorName(), "Product Color name is required");
            if (trim(color.getId()).isBlank()) color.setId(UUID.randomUUID().toString());
            color.setProductColorMasterId(null);

            if (!ids.add(color.getId())) {
                throw new OrderBomMprValidationException("Duplicate Product Color id: " + color.getId());
            }

            Integer sequence = color.getSequence();
            if (sequence == null || sequence <= 0) {
                throw new OrderBomMprValidationException("Product Color sequence must be greater than 0: " + name);
            }
            if (!sequences.add(sequence)) {
                throw new OrderBomMprValidationException("Duplicate Product Color sequence: " + sequence);
            }
            validateLocalChildColors(color);
            limitText(name, 100, "Product Color");
            limitText(color.getPatternNumber(), 100, "Pattern Number");
            limitText(color.getSeason(), 50, "Season");
            limitText(color.getStyleNumber(), 100, "Style Number");
        }
    }

    private void validatePackingCollection(List<BomPacking> packings, boolean submitting) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        LinkedHashSet<Integer> sequences = new LinkedHashSet<>();
        for (BomPacking packing : safe(packings)) {
            if (packing == null) throw new OrderBomMprValidationException("Packing contains an empty item");
            String name = required(packing.getPackingName(), "Packing name is required");
            Integer sequence = packing.getSequence();
            if (sequence == null || sequence <= 0) {
                throw new OrderBomMprValidationException("Packing sequence must be greater than 0: " + name);
            }
            if (!names.add(normalize(name))) {
                throw new OrderBomMprValidationException("Duplicate Packing name: " + name);
            }
            if (!sequences.add(sequence)) {
                throw new OrderBomMprValidationException("Duplicate Packing sequence: " + sequence);
            }
            limitText(name, 200, "Packing name");
            if (submitting && trim(packing.getId()).isBlank()) {
                throw new OrderBomMprValidationException("Packing id is missing: " + name);
            }
        }
    }


    private void validateProductColorSequence(BomDocument bom, String excludedProductColorId, int sequence) {
        if (sequence <= 0) throw new OrderBomMprValidationException("Product Color sequence must be greater than 0");
        for (BomProductColor existing : ensureProductColorsList(bom)) {
            if (existing == null || Objects.equals(excludedProductColorId, existing.getId())) continue;
            if (existing.getSequence() != null && existing.getSequence() == sequence) {
                throw new OrderBomMprValidationException("Product Color sequence already exists in this BOM: " + sequence);
            }
        }
    }

    private int nextPackingSequence(BomDocument bom) {
        int maxSequence = 0;
        for (BomPacking existing : ensurePackings(bom)) {
            if (existing != null && existing.getSequence() != null) {
                maxSequence = Math.max(maxSequence, existing.getSequence());
            }
        }
        return maxSequence + 1;
    }

    private void validatePackingIdentity(BomDocument bom, String excludedPackingId, String packingName, int sequence) {
        if (sequence <= 0) throw new OrderBomMprValidationException("Packing sequence must be greater than 0");
        String wantedName = normalize(packingName);
        for (BomPacking existing : ensurePackings(bom)) {
            if (Objects.equals(excludedPackingId, existing.getId())) continue;
            if (wantedName.equals(normalize(existing.getPackingName()))) {
                throw new OrderBomMprValidationException("Packing name already exists in this BOM: " + packingName);
            }
            if (existing.getSequence() != null && existing.getSequence() == sequence) {
                throw new OrderBomMprValidationException("Packing sequence already exists in this BOM: " + sequence);
            }
        }
    }

    private List<String> collectAttachmentStoredNames(BomDocument bom) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (BomAttachment item : ensureAttachments(bom)) addStoredName(names, item);
        for (BomPacking packing : ensurePackings(bom)) {
            for (BomAttachment item : ensureAttachments(packing)) addStoredName(names, item);
            for (BomLine line : ensureLines(packing)) {
                for (BomAttachment item : ensureAttachments(line)) addStoredName(names, item);
            }
        }
        for (BomLine line : ensureCoreLines(bom)) {
            for (BomAttachment item : ensureAttachments(line)) addStoredName(names, item);
        }
        return new ArrayList<>(names);
    }

    private void addStoredName(LinkedHashSet<String> names, BomAttachment attachment) {
        if (attachment != null && hasText(attachment.getStoredFileName())) names.add(attachment.getStoredFileName());
    }

    private List<BomImage> collectLineImages(BomDocument bom) {
        List<BomImage> images = new ArrayList<>();
        forEachLine(bom, line -> {
            if (line.getPrimaryImage() != null) images.add(line.getPrimaryImage());
        });
        return images;
    }

    private void validateManualLine(BomLine line) {
        if (line == null) throw new OrderBomMprValidationException("BOM line is empty");
        boolean detail = line.isDetailLine();
        if (!detail && trim(line.getMaterialType()).isBlank()) {
            throw new OrderBomMprValidationException("Material type is required for a material-group line");
        }
        if (detail && line.getMaterialGroupNo() == null) {
            throw new OrderBomMprValidationException("Detail line must be linked to a material group number");
        }
        if (line.getMaterialGroupNo() != null && line.getMaterialGroupNo() <= 0) {
            throw new OrderBomMprValidationException("Material group number must be greater than 0");
        }
        validateDecimal(line.getDimensionX(), "Dimension X");
        validateDecimal(line.getDimensionY(), "Dimension Y");
        validateDecimal(line.getQuantity(), "Quantity");
        validateDecimal(line.getCosting(), "Costing");
        validateDecimal(line.getDetailConsumption(), "Detail Consumption");
        validateDecimal(line.getConsumptionNet(), "Consumption Net");

        limitText(line.getMaterialType(), 100, "Material Type");
        limitText(line.getSapCode(), 100, "SAP Code");
        limitText(line.getDetailNo(), 100, "Detail No");
        limitText(line.getPosition(), 100, "Position");
        limitText(line.getPositionDescription(), 500, "Position Description");
        limitText(line.getPositionDescriptionExtra(), 500, "Position Description Extra");
        limitText(line.getPieceCode(), 50, "Piece Code");
        limitText(line.getDirection(), 50, "Direction");
        limitText(line.getCostingUnit(), 50, "Costing Unit");
        limitText(line.getConsumptionUnit(), 50, "Consumption Unit");
        limitText(line.getBomRemark(), 1000, "BOM Remark");
        limitText(line.getAdditionalRemark(), 1000, "Additional Remark");
    }

    private void validateDecimal(BigDecimal value, String label) {
        if (value == null) return;
        if (value.signum() < 0) throw new OrderBomMprValidationException(label + " must not be negative");

        // Preserve the decimal precision supplied by Excel or the UI.
        // Do not reject or round values because they contain more than 6 decimal places.
        BigDecimal normalized = value.stripTrailingZeros();
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (integerDigits > 18) throw new OrderBomMprValidationException(label + " is too large");
    }

    private void limitText(String value, int max, String label) {
        if (value != null && value.trim().length() > max) {
            throw new OrderBomMprValidationException(label + " must not exceed " + max + " characters");
        }
    }

    private String normalizeScope(String value) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "COLOR", "PACKING", "LINE" -> normalized;
            default -> "BOM";
        };
    }

    private boolean isImage(String fileName, String contentType) {
        String descriptor = (String.valueOf(fileName) + " " + String.valueOf(contentType)).toLowerCase(Locale.ROOT);
        return descriptor.contains("image/") || descriptor.matches(".*\\.(png|jpe?g|gif|webp|bmp|emf|wmf)(\\s.*)?$");
    }

    private boolean isImageAttachment(BomAttachment attachment) {
        String contentType = trim(attachment.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = trim(attachment.getOriginalFileName()).toLowerCase(Locale.ROOT);
        return contentType.startsWith("image/") || fileName.matches(".*\\.(png|jpe?g|gif|webp|bmp|emf|wmf)$");
    }

    private void requireLlBeanImplementation(String buyerKey, String feature) {
        String normalizedBuyerKey = BuyerKeys.legacyDefault(buyerKey);
        if (!BuyerKeys.LL_BEAN.equals(normalizedBuyerKey)) {
            throw new OrderBomMprValidationException(
                    feature + " is currently configured for L.L.BEAN only. "
                            + "Buyer strategy has not been configured for " + normalizedBuyerKey
            );
        }
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

    public record LineImageResource(Resource resource, String fileName, String contentType) { }
}
