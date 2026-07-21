package org.bsl.sales.service;

import org.bsl.sales.dto.BomLinePageResponse;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineDocument;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.repository.BomLineDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Persistence boundary for the optimized bom_lines collection. */
@Service
public class BomLineStore {
    public static final String SEPARATE = "SEPARATE";

    private final BomLineDocumentRepository repository;
    private final BomImageStorageService imageStorage;

    public BomLineStore(BomLineDocumentRepository repository, BomImageStorageService imageStorage) {
        this.repository = repository;
        this.imageStorage = imageStorage;
    }

    public boolean isSeparate(BomDocument bom) {
        return bom != null && SEPARATE.equalsIgnoreCase(bom.getLineStorageMode());
    }

    /** Loads all lines only for internal workflows such as MPR and Excel export. */
    public BomDocument hydrate(BomDocument bom) {
        if (bom == null || !isSeparate(bom)) return bom;
        List<BomLine> core = new ArrayList<>();
        Map<String, List<BomLine>> packingLines = new LinkedHashMap<>();
        for (BomLineDocument row : repository.findByBomIdOrderBySortOrderAsc(bom.getId())) {
            BomLine line = prepareHydrated(row.getLine(), bom.getId());
            if (row.getPackingId() == null || row.getPackingId().isBlank()) core.add(line);
            else packingLines.computeIfAbsent(row.getPackingId(), ignored -> new ArrayList<>()).add(line);
        }
        bom.setCoreLines(core);
        for (BomPacking packing : safe(bom.getPackings())) {
            List<BomLine> lines = packingLines.getOrDefault(packing.getId(), new ArrayList<>());
            packing.setLines(lines);
            packing.setLineCount(lines.size());
        }
        return bom;
    }

    /** Replaces the line collection after an aggregate edit/import, then updates lightweight counters. */
    public void replaceAll(BomDocument bom) {
        if (bom == null || bom.getId() == null) return;
        List<BomLineDocument> documents = new ArrayList<>();
        long sort = 0;
        long imageCount = 0;

        for (BomLine line : safe(bom.getCoreLines())) {
            prepareForStorage(line);
            documents.add(document(bom.getId(), null, sort++, line));
            if (line.getPrimaryImage() != null) imageCount++;
        }
        bom.setCoreLineCount(safe(bom.getCoreLines()).size());

        for (BomPacking packing : safe(bom.getPackings())) {
            List<BomLine> lines = safe(packing.getLines());
            packing.setLineCount(lines.size());
            for (BomLine line : lines) {
                prepareForStorage(line);
                documents.add(document(bom.getId(), packing.getId(), sort++, line));
                if (line.getPrimaryImage() != null) imageCount++;
            }
        }

        // Upsert the new snapshot first, then remove rows that no longer exist. A failed save therefore
        // never leaves the BOM without its previous material rows.
        Set<String> existingIds = repository.findByBomIdOrderBySortOrderAsc(bom.getId()).stream()
                .map(BomLineDocument::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!documents.isEmpty()) repository.saveAll(documents);
        Set<String> nextIds = documents.stream().map(BomLineDocument::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        existingIds.removeAll(nextIds);
        if (!existingIds.isEmpty()) repository.deleteAllById(existingIds);
        bom.setLineStorageMode(SEPARATE);
        bom.setLineCount(documents.size());
        bom.setImageCount(imageCount);
    }

    public void compactForStorage(BomDocument bom) {
        if (bom == null) return;
        bom.setCoreLines(new ArrayList<>());
        for (BomPacking packing : safe(bom.getPackings())) packing.setLines(new ArrayList<>());
    }

    public BomLinePageResponse page(String bomId, String packingId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<BomLineDocument> result = packingId == null || packingId.isBlank() || "__CORE__".equalsIgnoreCase(packingId)
                ? repository.findByBomIdAndPackingIdIsNullOrderBySortOrderAsc(bomId, pageable)
                : repository.findByBomIdAndPackingIdOrderBySortOrderAsc(bomId, packingId, pageable);
        List<BomLine> items = result.getContent().stream().map(row -> prepareForTableApi(row.getLine(), bomId)).toList();
        return new BomLinePageResponse(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    public BomLineDocument findDocument(String bomId, String lineId) {
        return repository.findByBomIdAndId(bomId, lineId)
                .orElseThrow(() -> new OrderBomMprNotFoundException("BOM line not found"));
    }

    public BomLine saveDocument(BomLineDocument document) {
        prepareForStorage(document.getLine());
        BomLineDocument saved = repository.save(document);
        return prepareForTableApi(saved.getLine(), saved.getBomId());
    }

    public void deleteByBomId(String bomId) { repository.deleteByBomId(bomId); }
    public void deleteByPacking(String bomId, String packingId) { repository.deleteByBomIdAndPackingId(bomId, packingId); }

    private BomLineDocument document(String bomId, String packingId, long sortOrder, BomLine line) {
        BomLineDocument document = new BomLineDocument();
        document.setId(line.getId());
        document.setBomId(bomId);
        document.setPackingId(packingId);
        document.setSortOrder(sortOrder);
        document.setLine(line);
        return document;
    }

    private void prepareForStorage(BomLine line) {
        if (line == null) return;
        line.setAttachmentCount(safe(line.getAttachments()).size());
        if (line.getPrimaryImage() != null) {
            line.getPrimaryImage().setOriginalUrl(null);
            line.getPrimaryImage().setPreviewUrl(null);
            line.getPrimaryImage().setThumbnailUrl(null);
        }
    }

    private BomLine prepareHydrated(BomLine line, String bomId) {
        if (line == null) return null;
        line.setAttachmentCount(safe(line.getAttachments()).size());
        imageStorage.bindUrls(line.getPrimaryImage(), bomId, line.getId());
        return line;
    }

    /** Table pages carry only the attachment count; file metadata is loaded through dedicated endpoints. */
    private BomLine prepareForTableApi(BomLine line, String bomId) {
        line = prepareHydrated(line, bomId);
        if (line != null) line.setAttachments(new ArrayList<>());
        return line;
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
