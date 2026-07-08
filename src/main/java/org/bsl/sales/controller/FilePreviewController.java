package org.bsl.sales.controller;

import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/files")
public class FilePreviewController {

    private static final Path FILES_DIR = Paths.get("files").toAbsolutePath().normalize();
    private static final Path UPLOADS_DIR = Paths.get("uploads").toAbsolutePath().normalize();
    private static final Path PREVIEW_CACHE_DIR = Paths.get("preview-cache").toAbsolutePath().normalize();
    private static final Duration CONVERT_TIMEOUT = Duration.ofSeconds(90);

    @GetMapping("/preview-pdf")
    public ResponseEntity<?> previewPdf(@RequestParam String fileUrl) {
        Path tempDir = null;

        try {
            Files.createDirectories(PREVIEW_CACHE_DIR);

            Path sourcePath = resolveSafeLocalPath(fileUrl);

            if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "message", "File not found",
                                "resolvedPath", sourcePath.toString()
                        ));
            }

            String extension = getExtension(sourcePath.getFileName().toString());

            if ("pdf".equals(extension)) {
                return pdfResponse(Files.readAllBytes(sourcePath), sourcePath.getFileName().toString(), false);
            }

            if (!isConvertibleOfficeFile(extension)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Preview is only supported for PDF, Word, Excel, and PowerPoint files"));
            }

            Path cachedPdfPath = getCachedPdfPath(sourcePath);

            if (Files.exists(cachedPdfPath) && Files.size(cachedPdfPath) > 0) {
                return pdfResponse(
                        Files.readAllBytes(cachedPdfPath),
                        removeExtension(sourcePath.getFileName().toString()) + ".pdf",
                        true
                );
            }

            String officeCommand = findOfficeCommand();

            if (officeCommand == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "message", "LibreOffice/soffice is not installed or not found on the server",
                                "hint", "Set LIBREOFFICE_PATH to C:\\Program Files\\LibreOffice\\program\\soffice.exe"
                        ));
            }

            tempDir = Files.createTempDirectory("office-preview-");
            Path profileDir = tempDir.resolve("lo-profile");
            Files.createDirectories(profileDir);

            Path safeInput = tempDir.resolve("input." + extension);

            if (isSpreadsheetFile(extension)) {
                prepareSpreadsheetForSinglePage(sourcePath, safeInput);
            } else {
                Files.copy(sourcePath, safeInput, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> command = new ArrayList<>();
            command.add(officeCommand);
            command.add("-env:UserInstallation=" + profileDir.toUri());
            command.add("--headless");
            command.add("--nologo");
            command.add("--nofirststartwizard");
            command.add("--convert-to");
            command.add(getPdfConvertFilter(extension));
            command.add("--outdir");
            command.add(tempDir.toString());
            command.add(safeInput.toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(CONVERT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "message", "Convert to PDF timed out",
                                "libreOffice", officeCommand
                        ));
            }

            if (process.exitValue() != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "message", "Failed to convert file to PDF",
                                "libreOffice", officeCommand,
                                "output", processOutput
                        ));
            }

            Path pdfPath = tempDir.resolve("input.pdf");

            if (!Files.exists(pdfPath)) {
                pdfPath = findAnyPdf(tempDir);
            }

            if (pdfPath == null || !Files.exists(pdfPath)) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of(
                                "message", "Converted PDF file was not created",
                                "libreOffice", officeCommand,
                                "output", processOutput
                        ));
            }

            Files.copy(pdfPath, cachedPdfPath, StandardCopyOption.REPLACE_EXISTING);

            byte[] bytes = Files.readAllBytes(cachedPdfPath);
            String pdfFileName = removeExtension(sourcePath.getFileName().toString()) + ".pdf";

            return pdfResponse(bytes, pdfFileName, false);

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to preview file: " + ex.getMessage()));
        } finally {
            if (tempDir != null) {
                deleteDirectoryQuietly(tempDir);
            }
        }
    }

    private Path getCachedPdfPath(Path sourcePath) throws IOException {
        String fileName = sourcePath.getFileName().toString();
        String baseName = removeExtension(fileName).replaceAll("[^a-zA-Z0-9._-]", "_");
        long lastModified = Files.getLastModifiedTime(sourcePath).toMillis();
        long size = Files.size(sourcePath);
        String cacheFileName = baseName + "_" + lastModified + "_" + size + ".pdf";

        return PREVIEW_CACHE_DIR.resolve(cacheFileName).normalize();
    }

    /**
     * Strong Excel handling:
     * - Fit every sheet to 1 page wide and 1 page tall.
     * - LibreOffice then exports with SinglePageSheets=true.
     */
    private void prepareSpreadsheetForSinglePage(Path sourcePath, Path safeInput) throws IOException {
        try (
                InputStream inputStream = Files.newInputStream(sourcePath);
                Workbook workbook = WorkbookFactory.create(inputStream)
        ) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);

                sheet.setAutobreaks(true);
                sheet.setFitToPage(true);

                PrintSetup printSetup = sheet.getPrintSetup();
                printSetup.setFitWidth((short) 1);
                printSetup.setFitHeight((short) 1);
                printSetup.setLandscape(true);
                printSetup.setPaperSize(PrintSetup.A4_PAPERSIZE);

                sheet.setMargin(Sheet.LeftMargin, 0.20);
                sheet.setMargin(Sheet.RightMargin, 0.20);
                sheet.setMargin(Sheet.TopMargin, 0.25);
                sheet.setMargin(Sheet.BottomMargin, 0.25);

                workbook.setPrintArea(i, 0, getLastUsedColumn(sheet), 0, Math.max(sheet.getLastRowNum(), 0));
            }

            try (OutputStream outputStream = Files.newOutputStream(safeInput)) {
                workbook.write(outputStream);
            }
        } catch (Exception ex) {
            Files.copy(sourcePath, safeInput, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int getLastUsedColumn(Sheet sheet) {
        int maxColumn = 0;

        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (sheet.getRow(rowIndex) != null) {
                maxColumn = Math.max(maxColumn, sheet.getRow(rowIndex).getLastCellNum() - 1);
            }
        }

        return Math.max(maxColumn, 0);
    }

    private String getPdfConvertFilter(String extension) {
        if (isSpreadsheetFile(extension)) {
            return "pdf:calc_pdf_Export:{\"SinglePageSheets\":{\"type\":\"boolean\",\"value\":\"true\"}}";
        }

        if ("ppt".equals(extension) || "pptx".equals(extension)) {
            return "pdf:impress_pdf_Export";
        }

        if ("doc".equals(extension) || "docx".equals(extension)) {
            return "pdf:writer_pdf_Export";
        }

        return "pdf";
    }

    private ResponseEntity<ByteArrayResource> pdfResponse(byte[] bytes, String fileName, boolean fromCache) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noCache())
                .header("X-Preview-Cache", fromCache ? "HIT" : "MISS")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFileName(fileName) + "\"")
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    private Path resolveSafeLocalPath(String rawFileUrl) throws Exception {
        if (rawFileUrl == null || rawFileUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("fileUrl is required");
        }

        String value = rawFileUrl.trim();

        if (startsWithHttp(value)) {
            value = extractPathFromAbsoluteUrl(value);
        }

        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }

        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }

        value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        String normalizedUrl = value.replace('\\', '/');

        Path baseDir;
        String relativePath;

        if (normalizedUrl.startsWith("/files/")) {
            baseDir = FILES_DIR;
            relativePath = normalizedUrl.substring("/files/".length());
        } else if (normalizedUrl.startsWith("files/")) {
            baseDir = FILES_DIR;
            relativePath = normalizedUrl.substring("files/".length());
        } else if (normalizedUrl.startsWith("/uploads/")) {
            baseDir = UPLOADS_DIR;
            relativePath = normalizedUrl.substring("/uploads/".length());
        } else if (normalizedUrl.startsWith("uploads/")) {
            baseDir = UPLOADS_DIR;
            relativePath = normalizedUrl.substring("uploads/".length());
        } else {
            throw new IllegalArgumentException("Only /files and /uploads paths are allowed. Received: " + normalizedUrl);
        }

        Path resolvedPath = baseDir.resolve(relativePath).normalize();

        if (!resolvedPath.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid file path");
        }

        return resolvedPath;
    }

    private boolean startsWithHttp(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String extractPathFromAbsoluteUrl(String absoluteUrl) {
        try {
            URI uri = URI.create(absoluteUrl.replace(" ", "%20"));
            String rawPath = uri.getRawPath();

            if (rawPath != null && !rawPath.trim().isEmpty()) {
                return rawPath;
            }
        } catch (Exception ignored) {
        }

        int schemeIndex = absoluteUrl.indexOf("://");

        if (schemeIndex >= 0) {
            int pathStart = absoluteUrl.indexOf('/', schemeIndex + 3);

            if (pathStart >= 0) {
                return absoluteUrl.substring(pathStart);
            }
        }

        return absoluteUrl;
    }

    private String findOfficeCommand() {
        String envLibreOfficePath = normalizeEnvPath(System.getenv("LIBREOFFICE_PATH"));

        if (envLibreOfficePath != null && Files.exists(Paths.get(envLibreOfficePath))) {
            return envLibreOfficePath;
        }

        String envSofficePath = normalizeEnvPath(System.getenv("SOFFICE_PATH"));

        if (envSofficePath != null && Files.exists(Paths.get(envSofficePath))) {
            return envSofficePath;
        }

        String windowsPath = "C:\\Program Files\\LibreOffice\\program\\soffice.exe";

        if (Files.exists(Paths.get(windowsPath))) {
            return windowsPath;
        }

        String windowsX86Path = "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe";

        if (Files.exists(Paths.get(windowsX86Path))) {
            return windowsX86Path;
        }

        if (isCommandAvailable("libreoffice")) {
            return "libreoffice";
        }

        if (isCommandAvailable("soffice")) {
            return "soffice";
        }

        return null;
    }

    private String normalizeEnvPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        return value.trim().replace("\"", "");
    }

    private boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            return finished && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private Path findAnyPdf(Path tempDir) throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            return stream
                    .filter(path -> "pdf".equals(getExtension(path.getFileName().toString())))
                    .findFirst()
                    .orElse(null);
        }
    }

    private boolean isConvertibleOfficeFile(String extension) {
        return "doc".equals(extension)
                || "docx".equals(extension)
                || "xls".equals(extension)
                || "xlsx".equals(extension)
                || "ppt".equals(extension)
                || "pptx".equals(extension);
    }

    private boolean isSpreadsheetFile(String extension) {
        return "xls".equals(extension) || "xlsx".equals(extension);
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String removeExtension(String fileName) {
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');

        if (dotIndex <= 0) {
            return fileName == null ? "preview" : fileName;
        }

        return fileName.substring(0, dotIndex);
    }

    private String safeFileName(String fileName) {
        return String.valueOf(fileName == null ? "preview.pdf" : fileName)
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\"", "_");
    }

    private void deleteDirectoryQuietly(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception ignored) {
        }
    }
}
