package org.bsl.sales.service;

import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Local storage for uploaded BOM workbooks, attachments, and Product Color master images. */
@Service
public class BomFileStorageService {

    @Value("${app.bom.upload-dir:uploads/bom}")
    private String uploadDirectory;

    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OrderBomMprValidationException("Upload file is required");
        }
        try {
            return storeStream(
                    file.getInputStream(),
                    sanitize(file.getOriginalFilename()),
                    file.getContentType(),
                    file.getSize()
            );
        } catch (IOException ex) {
            throw new OrderBomMprValidationException("Cannot store upload: " + ex.getMessage());
        }
    }

    /** Stores image bytes extracted from an imported Excel workbook. */
    public StoredFile storeBytes(byte[] bytes, String originalFileName, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new OrderBomMprValidationException("Attachment data is required");
        }
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return storeStream(input, sanitize(originalFileName), contentType, bytes.length);
        } catch (IOException ex) {
            throw new OrderBomMprValidationException("Cannot store attachment: " + ex.getMessage());
        }
    }


    /** Copies an already stored BOM attachment into a new independent Product Color Master image file. */
    public StoredFile copyStoredFile(String storedFileName, String originalFileName, String contentType, long size) {
        try {
            Path root = Path.of(uploadDirectory).toAbsolutePath().normalize();
            Path source = root.resolve(storedFileName == null ? "" : storedFileName).normalize();
            if (!source.startsWith(root) || !Files.exists(source) || !Files.isRegularFile(source)) {
                throw new OrderBomMprNotFoundException("Uploaded file was not found");
            }
            long actualSize = size > 0 ? size : Files.size(source);
            try (InputStream input = Files.newInputStream(source)) {
                return storeStream(input, sanitize(originalFileName), contentType, actualSize);
            }
        } catch (OrderBomMprNotFoundException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new OrderBomMprValidationException("Cannot copy stored file: " + ex.getMessage());
        }
    }

    public Resource load(String storedFileName) {
        try {
            Path root = Path.of(uploadDirectory).toAbsolutePath().normalize();
            Path target = root.resolve(storedFileName == null ? "" : storedFileName).normalize();
            if (!target.startsWith(root) || !Files.exists(target)) {
                throw new OrderBomMprNotFoundException("Uploaded file was not found");
            }
            return new FileSystemResource(target);
        } catch (OrderBomMprNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderBomMprNotFoundException("Uploaded file was not found");
        }
    }

    public void deleteQuietly(String storedFileName) {
        if (storedFileName == null || storedFileName.isBlank()) return;
        try {
            Path root = Path.of(uploadDirectory).toAbsolutePath().normalize();
            Path target = root.resolve(storedFileName).normalize();
            if (target.startsWith(root)) Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // Database state remains correct even when an old physical file cannot be removed.
        }
    }

    private StoredFile storeStream(InputStream input, String original, String contentType, long size) throws IOException {
        Path root = Path.of(uploadDirectory).toAbsolutePath().normalize();
        Files.createDirectories(root);

        String stored = UUID.randomUUID() + extensionOf(original);
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) throw new OrderBomMprValidationException("Invalid upload file name");

        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(original, stored, contentType, size);
    }

    private String sanitize(String name) {
        String fallback = "upload";
        if (name == null || name.isBlank()) return fallback;
        return Path.of(name).getFileName().toString().replaceAll("[^A-Za-z0-9._() -]", "_");
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    public record StoredFile(String originalFileName, String storedFileName, String contentType, long size) { }
}
