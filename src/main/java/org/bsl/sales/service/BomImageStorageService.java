package org.bsl.sales.service;

import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.BomImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Stores BOM line images outside MongoDB and produces browser-friendly PNG derivatives. */
@Service
public class BomImageStorageService {
    private static final int PREVIEW_MAX = 1200;
    private static final int THUMBNAIL_MAX = 180;
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;

    private final BomFileStorageService fileStorage;

    /** Optional absolute path, for example C:\\Program Files\\LibreOffice\\program\\soffice.exe. */
    @Value("${app.bom.libreoffice-path:}")
    private String configuredLibreOfficePath;

    public BomImageStorageService(BomFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    public BomImage store(MultipartFile file, boolean importedFromExcel, Integer sourceRow, Integer sourceColumn) {
        if (file == null || file.isEmpty()) {
            throw new OrderBomMprValidationException("BOM line image is required");
        }
        validateImage(file.getOriginalFilename(), file.getContentType(), file.getSize());
        BomFileStorageService.StoredFile original = fileStorage.store(file);
        return createMetadata(original, importedFromExcel, sourceRow, sourceColumn);
    }

    public BomImage storeBytes(byte[] bytes, String fileName, String contentType, boolean importedFromExcel, Integer sourceRow, Integer sourceColumn) {
        long size = bytes == null ? 0L : bytes.length;
        String detectedExtension = detectExtension(fileName, contentType, bytes);
        String normalizedName = fileName == null || fileName.isBlank() ? "bom-line-image" : fileName;
        if (!normalizedName.matches(".*\\.[A-Za-z0-9]{2,5}$") && hasText(detectedExtension)) {
            normalizedName += "." + detectedExtension;
        }
        String normalizedType = normalizeContentType(contentType, normalizedName);
        validateImage(normalizedName, normalizedType, size);
        BomFileStorageService.StoredFile original = fileStorage.storeBytes(bytes, normalizedName, normalizedType);
        return createMetadata(original, importedFromExcel, sourceRow, sourceColumn);
    }

    private BomImage createMetadata(BomFileStorageService.StoredFile original, boolean importedFromExcel, Integer sourceRow, Integer sourceColumn) {
        BomImage image = new BomImage();
        image.setId(UUID.randomUUID().toString());
        image.setOriginalFileName(original.originalFileName());
        image.setOriginalStoredFileName(original.storedFileName());
        image.setOriginalContentType(normalizeContentType(original.contentType(), original.originalFileName()));
        image.setOriginalSize(original.size());
        image.setImportedFromExcel(importedFromExcel);
        image.setSourceRowNumber(sourceRow);
        image.setSourceColumnIndex(sourceColumn);
        image.setUploadedBy(importedFromExcel ? "EXCEL_IMPORT" : RequestActor.current());
        image.setUpdatedAt(LocalDateTime.now());

        // Failure to create a derivative never loses the original. A later GET will retry conversion.
        ensureDerivatives(image);
        return image;
    }

    /**
     * Creates missing PNG preview/thumbnail files. This is intentionally public so old records that
     * were imported before LibreOffice was configured can repair themselves on their first GET.
     */
    public synchronized boolean ensureDerivatives(BomImage image) {
        if (image == null || !hasText(image.getOriginalStoredFileName())) return false;
        boolean needPreview = !hasText(image.getPreviewStoredFileName());
        boolean needThumbnail = !hasText(image.getThumbnailStoredFileName());
        if (!needPreview && !needThumbnail) return false;

        try {
            BufferedImage source = readBrowserImage(image);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return false;

            image.setWidth(source.getWidth());
            image.setHeight(source.getHeight());

            if (needPreview) {
                BomFileStorageService.StoredFile preview = storePng(resize(source, PREVIEW_MAX), "bom-line-preview.png");
                image.setPreviewStoredFileName(preview.storedFileName());
                image.setPreviewContentType("image/png");
            }
            if (needThumbnail) {
                BomFileStorageService.StoredFile thumbnail = storePng(resize(source, THUMBNAIL_MAX), "bom-line-thumbnail.png");
                image.setThumbnailStoredFileName(thumbnail.storedFileName());
                image.setThumbnailContentType("image/png");
            }
            image.setUpdatedAt(LocalDateTime.now());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private BufferedImage readBrowserImage(BomImage image) throws IOException, InterruptedException {
        Resource resource = fileStorage.load(image.getOriginalStoredFileName());
        byte[] bytes;
        try (var input = resource.getInputStream()) {
            bytes = input.readAllBytes();
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage direct = ImageIO.read(input);
            if (direct != null) return direct;
        }

        String extension = detectExtension(image.getOriginalFileName(), image.getOriginalContentType(), bytes);
        if (!"emf".equals(extension) && !"wmf".equals(extension)) return null;
        return convertVectorToPng(bytes, extension);
    }

    /**
     * The temporary source always receives an explicit .emf/.wmf extension. This also repairs old
     * stored files whose physical UUID name had no extension and therefore could not be detected by soffice.
     */
    private BufferedImage convertVectorToPng(byte[] bytes, String extension) throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("bom-image-convert-");
        Path source = temp.resolve("source." + extension);
        Files.write(source, bytes);

        try {
            for (String executable : libreOfficeCandidates()) {
                Path converted = runConverter(
                        List.of(executable, "--headless", "--convert-to", "png", "--outdir", temp.toString(), source.toString()),
                        temp
                );
                BufferedImage image = readPng(converted);
                if (image != null) return image;
            }

            // Optional ImageMagick fallback for servers where LibreOffice is unavailable.
            for (String executable : imageMagickCandidates()) {
                Path output = temp.resolve("imagemagick-output.png");
                Path converted = runConverter(List.of(executable, source.toString(), output.toString()), temp);
                BufferedImage image = readPng(converted == null && Files.exists(output) ? output : converted);
                if (image != null) return image;
            }
            return null;
        } finally {
            deleteTree(temp);
        }
    }

    private Path runConverter(List<String> command, Path temp) throws InterruptedException {
        try {
            // Remove stale output before trying the next converter candidate.
            try (var files = Files.list(temp)) {
                files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            }

            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(40, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            if (process.exitValue() != 0) return null;

            try (var files = Files.list(temp)) {
                return files
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException | IllegalThreadStateException ignored) {
            return null;
        }
    }

    private BufferedImage readPng(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException ignored) {
            return null;
        }
    }

    private List<String> libreOfficeCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, configuredLibreOfficePath);
        addCandidate(candidates, System.getenv("LIBREOFFICE_PATH"));
        addCandidate(candidates, "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        addCandidate(candidates, "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe");
        addCandidate(candidates, "/usr/bin/soffice");
        addCandidate(candidates, "/usr/local/bin/soffice");
        addCandidate(candidates, "soffice");
        return new ArrayList<>(candidates);
    }

    private List<String> imageMagickCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, System.getenv("IMAGEMAGICK_PATH"));
        addCandidate(candidates, "magick");
        return new ArrayList<>(candidates);
    }

    private void addCandidate(Set<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank()) candidates.add(candidate.trim());
    }

    private void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // Temporary conversion files are best-effort cleanup only.
        }
    }

    private BomFileStorageService.StoredFile storePng(BufferedImage image, String name) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer is unavailable");
            return fileStorage.storeBytes(output.toByteArray(), name, "image/png");
        }
    }

    private BufferedImage resize(BufferedImage source, int maxSize) {
        double factor = Math.min(1d, Math.min((double) maxSize / source.getWidth(), (double) maxSize / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    public Resource load(BomImage image, String variant) {
        if (image == null) throw new OrderBomMprValidationException("BOM line image was not found");
        String normalized = normalizeVariant(variant);
        if (!"original".equals(normalized)) ensureDerivatives(image);
        String stored = resolvedStoredFileName(image, normalized);
        if (!hasText(stored)) {
            throw new OrderBomMprValidationException(
                    "Cannot create a browser preview for this EMF/WMF image. Install LibreOffice and set LIBREOFFICE_PATH on the Backend server."
            );
        }
        return fileStorage.load(stored);
    }

    public String contentType(BomImage image, String variant) {
        String normalized = normalizeVariant(variant);
        return switch (normalized) {
            case "thumbnail" -> hasText(image.getThumbnailStoredFileName()) ? "image/png"
                    : hasText(image.getPreviewStoredFileName()) ? "image/png"
                    : browserSafeOriginal(image) ? normalizeContentType(image.getOriginalContentType(), image.getOriginalFileName()) : "";
            case "preview" -> hasText(image.getPreviewStoredFileName()) ? "image/png"
                    : browserSafeOriginal(image) ? normalizeContentType(image.getOriginalContentType(), image.getOriginalFileName()) : "";
            default -> normalizeContentType(image.getOriginalContentType(), image.getOriginalFileName());
        };
    }

    public String fileName(BomImage image, String variant) {
        String normalized = normalizeVariant(variant);
        if ("original".equals(normalized)) return originalFileNameWithExtension(image);
        if ("thumbnail".equals(normalized) && hasText(image.getThumbnailStoredFileName())) return "bom-line-thumbnail.png";
        if (hasText(image.getPreviewStoredFileName())) return "bom-line-preview.png";
        return browserSafeOriginal(image) ? originalFileNameWithExtension(image) : "bom-line-preview.png";
    }

    private String resolvedStoredFileName(BomImage image, String normalizedVariant) {
        return switch (normalizedVariant) {
            case "thumbnail" -> firstNonBlank(
                    image.getThumbnailStoredFileName(),
                    image.getPreviewStoredFileName(),
                    browserSafeOriginal(image) ? image.getOriginalStoredFileName() : ""
            );
            case "preview" -> firstNonBlank(
                    image.getPreviewStoredFileName(),
                    browserSafeOriginal(image) ? image.getOriginalStoredFileName() : ""
            );
            default -> image.getOriginalStoredFileName();
        };
    }

    private boolean browserSafeOriginal(BomImage image) {
        String descriptor = (String.valueOf(image.getOriginalContentType()) + " " + String.valueOf(image.getOriginalFileName())).toLowerCase(Locale.ROOT);
        return descriptor.contains("image/png")
                || descriptor.contains("image/jpeg")
                || descriptor.contains("image/jpg")
                || descriptor.contains("image/gif")
                || descriptor.contains("image/webp")
                || descriptor.contains("image/bmp")
                || descriptor.matches(".*\\.(png|jpe?g|gif|webp|bmp)(\\s.*)?$");
    }

    private String originalFileNameWithExtension(BomImage image) {
        String name = firstNonBlank(image.getOriginalFileName(), "bom-line-image");
        if (name.matches(".*\\.[A-Za-z0-9]{2,5}$")) return name;
        String extension = detectExtension(name, image.getOriginalContentType(), null);
        return hasText(extension) ? name + "." + extension : name;
    }

    public void bindUrls(BomImage image, String bomId, String lineId) {
        if (image == null) return;
        String base = "/api/boms/" + bomId + "/lines/" + lineId + "/image";
        image.setOriginalUrl(base + "/original");
        image.setPreviewUrl(base + "/preview");
        image.setThumbnailUrl(base + "/thumbnail");
    }

    public void delete(BomImage image) {
        if (image == null) return;
        fileStorage.deleteQuietly(image.getOriginalStoredFileName());
        fileStorage.deleteQuietly(image.getPreviewStoredFileName());
        fileStorage.deleteQuietly(image.getThumbnailStoredFileName());
    }

    private void validateImage(String name, String contentType, long size) {
        if (size <= 0) throw new OrderBomMprValidationException("BOM line image is empty");
        if (size > MAX_IMAGE_BYTES) throw new OrderBomMprValidationException("BOM line image must not exceed 25 MB");
        String descriptor = (String.valueOf(name) + " " + String.valueOf(contentType)).toLowerCase(Locale.ROOT);
        if (!(descriptor.contains("image/") || descriptor.matches(".*\\.(png|jpe?g|gif|webp|bmp|emf|wmf)(\\s.*)?$"))) {
            throw new OrderBomMprValidationException("The BOM Image column accepts image files only");
        }
    }

    private String normalizeVariant(String value) {
        String clean = value == null ? "preview" : value.trim().toLowerCase(Locale.ROOT);
        return switch (clean) { case "original", "thumbnail" -> clean; default -> "preview"; };
    }

    private String normalizeContentType(String contentType, String fileName) {
        String clean = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!clean.isBlank() && !"application/octet-stream".equals(clean)) return clean;
        String extension = detectExtension(fileName, clean, null);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "emf" -> "image/x-emf";
            case "wmf" -> "image/x-wmf";
            default -> "application/octet-stream";
        };
    }

    private String detectExtension(String fileName, String contentType, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String extension = name.substring(dot + 1).replaceAll("[^a-z0-9]", "");
            if (Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "emf", "wmf").contains(extension)) return extension;
        }

        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("emf")) return "emf";
        if (type.contains("wmf")) return "wmf";
        if (type.contains("png")) return "png";
        if (type.contains("jpeg") || type.contains("jpg")) return "jpg";
        if (type.contains("gif")) return "gif";
        if (type.contains("webp")) return "webp";
        if (type.contains("bmp")) return "bmp";

        if (bytes != null) {
            // EMF: ENHMETAHEADER signature 0x464D4520 (' EMF') at byte offset 40.
            if (bytes.length >= 44 && bytes[40] == 0x20 && bytes[41] == 0x45 && bytes[42] == 0x4D && bytes[43] == 0x46) return "emf";
            // Placeable WMF key 0x9AC6CDD7, little endian.
            if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xD7 && (bytes[1] & 0xFF) == 0xCD
                    && (bytes[2] & 0xFF) == 0xC6 && (bytes[3] & 0xFF) == 0x9A) return "wmf";
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
