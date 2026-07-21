package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight metadata for the main image shown in the BOM Image column.
 * Image bytes are stored by BomFileStorageService, never inside MongoDB.
 */
@Data
@NoArgsConstructor
public class BomImage {
    private String id;
    private String originalFileName;
    @JsonIgnore
    private String originalStoredFileName;
    private String originalContentType;
    private long originalSize;

    @JsonIgnore
    private String previewStoredFileName;
    private String previewContentType;
    @JsonIgnore
    private String thumbnailStoredFileName;
    private String thumbnailContentType;
    private Integer width;
    private Integer height;

    private boolean importedFromExcel;
    private Integer sourceRowNumber;
    private Integer sourceColumnIndex;
    private String uploadedBy;

    /** API URLs are metadata only and contain no binary image data. */
    private String originalUrl;
    private String previewUrl;
    private String thumbnailUrl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
