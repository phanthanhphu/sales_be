package org.bsl.sales.service;

import org.bsl.sales.dto.BomReviewDecisionRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.MprBomReview;
import org.bsl.sales.model.MprBomReviewChange;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.bsl.sales.model.ProductColorAttribute;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps Sales edits to BOM-backed MPR fields pending until the BOM team reviews them.
 * MPR-only fields (PO Qty, Ship To, Sales Comment, supplier values, etc.) are not
 * sent for BOM review because they do not belong to the source BOM line.
 */
@Service
public class MprBomReviewService {
    public static final String PENDING = "PENDING_BOM_REVIEW";
    public static final String APPLIED = "APPLIED_TO_BOM";
    public static final String RECHECK = "RECHECK_SALES";
    public static final String CANCELLED = "CANCELLED";

    private final MprDocumentRepository mprRepository;
    private final BomDocumentRepository bomRepository;
    private final BomLineStore lineStore;
    private final ProductColorMasterService productColorMasterService;

    public MprBomReviewService(
            MprDocumentRepository mprRepository,
            BomDocumentRepository bomRepository,
            BomLineStore lineStore,
            ProductColorMasterService productColorMasterService
    ) {
        this.mprRepository = mprRepository;
        this.bomRepository = bomRepository;
        this.lineStore = lineStore;
        this.productColorMasterService = productColorMasterService;
    }

    /**
     * Called after Sales saves one MPR row. It creates or refreshes a single
     * pending review for this MPR row when any BOM-backed value differs from
     * the current source BOM line.
     */
    public void capturePendingReview(MprLine mprLine) {
        if (mprLine == null || blank(mprLine.getBomId()) || blank(mprLine.getSourceLineId())) return;

        BomDocument bom = bomRepository.findById(mprLine.getBomId()).map(lineStore::hydrate).orElse(null);
        if (bom == null) return;
        BomLine source = findSourceLine(bom, mprLine.getSourceLineId());
        if (source == null) return;

        List<MprBomReviewChange> changes = buildChanges(bom, source, mprLine);
        List<MprBomReview> reviews = ensureReviews(mprLine);
        MprBomReview pending = latestByStatus(reviews, PENDING);

        if (changes.isEmpty()) {
            if (pending != null) {
                pending.setStatus(CANCELLED);
                pending.setReviewedBy(RequestActor.current());
                pending.setReviewedAt(LocalDateTime.now());
                pending.setReviewComment("Sales updated the MPR row so it matches the current BOM.");
            }
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (pending == null) {
            pending = new MprBomReview();
            pending.setId(UUID.randomUUID().toString());
            reviews.add(pending);
        }
        pending.setStatus(PENDING);
        pending.setBomId(bom.getId());
        pending.setSourceLineId(source.getId());
        pending.setPackingId(trim(mprLine.getPackingId()));
        pending.setPackingName(trim(mprLine.getPackingName()));
        pending.setProductColorId(trim(mprLine.getProductColorId()));
        pending.setStyleColor(trim(mprLine.getStyleColor()));
        pending.setMaterialLabel(firstNonBlank(mprLine.getMatFullDescription(), sourceDescription(source)));
        pending.setChanges(changes);
        pending.setRequestedBy(RequestActor.current());
        pending.setRequestedAt(now);
        pending.setReviewedBy("");
        pending.setReviewedAt(null);
        pending.setReviewComment("");
    }

    /** Lists all current and historical review items for one BOM, newest first. */
    public List<MprBomReview> listForBom(String bomId) {
        // Listing review metadata only needs the lightweight BOM header; do not hydrate material lines.
        BomDocument bom = bomRepository.findById(bomId)
                .orElseThrow(() -> new OrderBomMprNotFoundException("BOM not found"));
        MprDocument mpr = mprRepository.findByOrderId(bom.getOrderId()).orElse(null);
        if (mpr == null) return List.of();

        List<MprBomReview> result = new ArrayList<>();
        for (MprLine line : safe(mpr.getLines())) {
            if (line == null || !Objects.equals(bomId, line.getBomId())) continue;
            for (MprBomReview review : safe(line.getBomReviews())) {
                if (review != null) result.add(review);
            }
        }
        result.sort(Comparator.comparing(MprBomReview::getRequestedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /** Applies one pending Sales proposal to exactly one linked source BOM line. */
    public BomDocument applyToBom(String bomId, String reviewId, BomReviewDecisionRequest request) {
        ReviewContext context = resolveReview(bomId, reviewId);
        MprBomReview review = context.review();
        if (!PENDING.equalsIgnoreCase(review.getStatus())) {
            throw new OrderBomMprValidationException("Only a pending MPR review can be applied to BOM");
        }

        BomLine source = findSourceLine(context.bom(), review.getSourceLineId());
        if (source == null) {
            throw new OrderBomMprNotFoundException("Source BOM line for this MPR review no longer exists");
        }

        // Prevent a reviewer from overwriting a BOM value changed after Sales submitted this request.
        for (MprBomReviewChange change : safe(review.getChanges())) {
            String current = currentBomValue(context.bom(), source, review, change);
            if (!same(current, change.getBomValue())) {
                throw new OrderBomMprValidationException(
                        "BOM data changed after this Sales request for " + change.getLabel()
                                + ". Refresh and use Recheck Sales if the proposal is no longer valid."
                );
            }
        }

        for (MprBomReviewChange change : safe(review.getChanges())) {
            applyChange(context.bom(), source, review, change);
        }

        context.bom().setUpdatedAt(LocalDateTime.now());
        context.bom().setUpdatedBy(RequestActor.current());
        // Keep Product Color / Child Color master links canonical after a MAT COLOR approval.
        productColorMasterService.synchronizeFromBom(context.bom());
        lineStore.replaceAll(context.bom());
        lineStore.compactForStorage(context.bom());
        BomDocument savedBom = bomRepository.save(context.bom());

        review.setStatus(APPLIED);
        review.setReviewedBy(RequestActor.current());
        review.setReviewedAt(LocalDateTime.now());
        review.setReviewComment(trim(request == null ? null : request.comment()));
        context.mpr().setUpdatedAt(LocalDateTime.now());
        context.mpr().setUpdatedBy(RequestActor.current());
        mprRepository.save(context.mpr());
        return savedBom;
    }

    /** Sends one item back to Sales without changing BOM data. */
    public MprBomReview sendBackToSales(String bomId, String reviewId, BomReviewDecisionRequest request) {
        ReviewContext context = resolveReview(bomId, reviewId);
        MprBomReview review = context.review();
        if (!PENDING.equalsIgnoreCase(review.getStatus())) {
            throw new OrderBomMprValidationException("Only a pending MPR review can be returned to Sales");
        }

        review.setStatus(RECHECK);
        review.setReviewedBy(RequestActor.current());
        review.setReviewedAt(LocalDateTime.now());
        review.setReviewComment(firstNonBlank(
                request == null ? null : request.comment(),
                "Please recheck the proposed MPR data against BOM."
        ));
        context.mpr().setUpdatedAt(LocalDateTime.now());
        context.mpr().setUpdatedBy(RequestActor.current());
        mprRepository.save(context.mpr());
        return review;
    }

    private ReviewContext resolveReview(String bomId, String reviewId) {
        if (blank(reviewId)) throw new OrderBomMprValidationException("MPR review id is required");
        BomDocument bom = getBom(bomId);
        MprDocument mpr = mprRepository.findByOrderId(bom.getOrderId())
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR has not been created for this order"));

        for (MprLine line : safe(mpr.getLines())) {
            if (line == null || !Objects.equals(bomId, line.getBomId())) continue;
            for (MprBomReview review : safe(line.getBomReviews())) {
                if (review != null && reviewId.equals(review.getId())) {
                    return new ReviewContext(bom, mpr, line, review);
                }
            }
        }
        throw new OrderBomMprNotFoundException("MPR review item not found");
    }

    private BomDocument getBom(String bomId) {
        return bomRepository.findById(bomId)
                .map(lineStore::hydrate)
                .orElseThrow(() -> new OrderBomMprNotFoundException("BOM not found"));
    }

    private List<MprBomReviewChange> buildChanges(BomDocument bom, BomLine source, MprLine mprLine) {
        List<MprBomReviewChange> changes = new ArrayList<>();
        addChange(changes, "SAP_CODE", "SAP Code", "sapCode", source.getSapCode(), mprLine.getSapCode());
        addChange(changes, "MATERIAL_GROUP_NO", "BOM No", "materialGroupNo",
                valueOf(source.getMaterialGroupNo()), valueOf(mprLine.getBomLineNo()));
        addChange(changes, "MATERIAL_TYPE", "Material Type", "materialType", source.getMaterialType(), mprLine.getMaterialType());

        String descriptionField = descriptionField(source);
        addChange(changes, "MAT_FULL_DESCRIPTION", "MAT Full Description", descriptionField,
                sourceDescription(source), mprLine.getMatFullDescription());

        addChange(changes, "MAT_COLOR", "MAT Color", "productColorValue",
                currentMatColor(bom, source, mprLine), mprLine.getMatColor());
        addChange(changes, "MAT_UNIT", "MAT Unit", "consumptionUnit", source.getConsumptionUnit(), mprLine.getMatUnit());
        addChange(changes, "YIELD", "Yield", "consumptionNet",
                decimalText(source.getConsumptionNet()), decimalText(mprLine.getYield()));
        return changes;
    }

    private void addChange(
            List<MprBomReviewChange> target,
            String field,
            String label,
            String bomField,
            String bomValue,
            String salesValue
    ) {
        if (same(bomValue, salesValue)) return;
        MprBomReviewChange change = new MprBomReviewChange();
        change.setField(field);
        change.setLabel(label);
        change.setBomField(bomField);
        change.setBomValue(trim(bomValue));
        change.setSalesValue(trim(salesValue));
        target.add(change);
    }

    private String currentBomValue(BomDocument bom, BomLine source, MprBomReview review, MprBomReviewChange change) {
        if (change == null) return "";
        return switch (trim(change.getField())) {
            case "SAP_CODE" -> trim(source.getSapCode());
            case "MATERIAL_GROUP_NO" -> valueOf(source.getMaterialGroupNo());
            case "MATERIAL_TYPE" -> trim(source.getMaterialType());
            case "MAT_FULL_DESCRIPTION" -> readDescriptionField(source, change.getBomField());
            case "MAT_COLOR" -> currentMatColor(bom, source, review.getProductColorId(), review.getStyleColor());
            case "MAT_UNIT" -> trim(source.getConsumptionUnit());
            case "YIELD" -> decimalText(source.getConsumptionNet());
            default -> "";
        };
    }

    private void applyChange(BomDocument bom, BomLine source, MprBomReview review, MprBomReviewChange change) {
        String value = trim(change.getSalesValue());
        switch (trim(change.getField())) {
            case "SAP_CODE" -> source.setSapCode(value);
            case "MATERIAL_GROUP_NO" -> source.setMaterialGroupNo(parseInteger(value, "BOM No"));
            case "MATERIAL_TYPE" -> source.setMaterialType(value);
            case "MAT_FULL_DESCRIPTION" -> writeDescriptionField(source, change.getBomField(), value);
            case "MAT_COLOR" -> applyMatColor(bom, source, review.getProductColorId(), review.getStyleColor(), value);
            case "MAT_UNIT" -> source.setConsumptionUnit(value);
            case "YIELD" -> source.setConsumptionNet(parseDecimal(value, "Yield"));
            default -> throw new OrderBomMprValidationException("Unsupported BOM review field: " + change.getLabel());
        }
    }

    private void applyMatColor(
            BomDocument bom,
            BomLine source,
            String productColorId,
            String styleColor,
            String childColorValue
    ) {
        BomProductColor productColor = findProductColor(bom, productColorId, styleColor);
        if (productColor == null) {
            throw new OrderBomMprValidationException("Selected Product Color no longer exists in BOM");
        }

        String masterId = trim(productColor.getProductColorMasterId());
        ProductColorAttribute child = blank(masterId)
                ? null
                : productColorMasterService.findChildColor(masterId, "", childColorValue);
        if (!blank(masterId) && child == null) {
            throw new OrderBomMprValidationException(
                    "MAT Color must be an existing Child Color of Style Color " + productColor.getColorName()
                            + ". Add it to Product Color Master or use Recheck Sales."
            );
        }

        String value = child == null ? trim(childColorValue) : trim(child.getChildColor());
        String childId = child == null ? "" : trim(child.getId());
        List<BomLineColorValue> values = source.getProductColorValues() == null
                ? new ArrayList<>()
                : new ArrayList<>(source.getProductColorValues());
        BomLineColorValue linked = values.stream()
                .filter(item -> item != null && Objects.equals(productColor.getId(), item.getProductColorId()))
                .findFirst()
                .orElse(null);
        if (linked == null) {
            linked = new BomLineColorValue();
            linked.setProductColorId(productColor.getId());
            values.add(linked);
        }
        linked.setChildColorId(childId);
        linked.setValue(value);
        source.setProductColorValues(values);

        Map<String, String> legacy = source.getColorValues() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.getColorValues());
        legacy.put(productColor.getColorName(), value);
        source.setColorValues(legacy);
    }

    private String currentMatColor(BomDocument bom, BomLine source, MprLine mprLine) {
        return currentMatColor(bom, source, mprLine == null ? "" : mprLine.getProductColorId(),
                mprLine == null ? "" : mprLine.getStyleColor());
    }

    private String currentMatColor(BomDocument bom, BomLine source, String productColorId, String styleColor) {
        BomProductColor productColor = findProductColor(bom, productColorId, styleColor);
        if (productColor != null) {
            for (BomLineColorValue value : safe(source.getProductColorValues())) {
                if (value != null && Objects.equals(productColor.getId(), value.getProductColorId())) {
                    return trim(value.getValue());
                }
            }
            if (source.getColorValues() != null) {
                for (Map.Entry<String, String> entry : source.getColorValues().entrySet()) {
                    if (same(entry.getKey(), productColor.getColorName())) return trim(entry.getValue());
                }
            }
        }
        return "";
    }

    private BomProductColor findProductColor(BomDocument bom, String productColorId, String styleColor) {
        for (BomProductColor item : safe(bom.getProductColors())) {
            if (item == null) continue;
            if (!blank(productColorId) && productColorId.equals(item.getId())) return item;
            if (!blank(styleColor) && same(styleColor, item.getColorName())) return item;
        }
        return null;
    }

    private BomLine findSourceLine(BomDocument bom, String sourceLineId) {
        for (BomLine line : safe(bom.getCoreLines())) {
            if (line != null && Objects.equals(sourceLineId, line.getId())) return line;
        }
        for (BomPacking packing : safe(bom.getPackings())) {
            for (BomLine line : safe(packing == null ? null : packing.getLines())) {
                if (line != null && Objects.equals(sourceLineId, line.getId())) return line;
            }
        }
        return null;
    }

    private String descriptionField(BomLine source) {
        if (!blank(source.getPosition())) return "position";
        if (!blank(source.getPositionDescription())) return "positionDescription";
        if (!blank(source.getPositionDescriptionExtra())) return "positionDescriptionExtra";
        return "positionDescription";
    }

    private String sourceDescription(BomLine source) {
        return firstNonBlank(source.getPosition(), source.getPositionDescription(), source.getPositionDescriptionExtra());
    }

    private String readDescriptionField(BomLine source, String field) {
        return switch (trim(field)) {
            case "position" -> trim(source.getPosition());
            case "positionDescriptionExtra" -> trim(source.getPositionDescriptionExtra());
            default -> trim(source.getPositionDescription());
        };
    }

    private void writeDescriptionField(BomLine source, String field, String value) {
        switch (trim(field)) {
            case "position" -> source.setPosition(value);
            case "positionDescriptionExtra" -> source.setPositionDescriptionExtra(value);
            default -> source.setPositionDescription(value);
        }
    }

    private MprBomReview latestByStatus(List<MprBomReview> reviews, String status) {
        MprBomReview result = null;
        for (MprBomReview review : safe(reviews)) {
            if (review == null || !status.equalsIgnoreCase(review.getStatus())) continue;
            if (result == null || compareTime(review.getRequestedAt(), result.getRequestedAt()) > 0) result = review;
        }
        return result;
    }

    private int compareTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private List<MprBomReview> ensureReviews(MprLine line) {
        if (line.getBomReviews() == null) line.setBomReviews(new ArrayList<>());
        return line.getBomReviews();
    }

    private Integer parseInteger(String value, String field) {
        if (blank(value)) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new OrderBomMprValidationException(field + " must be a whole number");
        }
    }

    private BigDecimal parseDecimal(String value, String field) {
        if (blank(value)) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new OrderBomMprValidationException(field + " must be a valid number");
        }
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean same(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalize(String value) {
        return trim(value).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ReviewContext(BomDocument bom, MprDocument mpr, MprLine line, MprBomReview review) { }
}
