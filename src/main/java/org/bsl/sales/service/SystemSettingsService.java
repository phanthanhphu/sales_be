package org.bsl.sales.service;

import org.bsl.sales.dto.SystemSettingsRequest;
import org.bsl.sales.dto.SystemSettingsResponse;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.SystemSettings;
import org.bsl.sales.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class SystemSettingsService {
    private static final long MAX_LOGO_BYTES = 2L * 1024L * 1024L;
    private static final Set<String> DATE_FORMATS = Set.of("DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD");
    private static final Set<String> NUMBER_FORMATS = Set.of("1,234.56", "1.234,56", "1 234,56");
    private static final Set<String> LANGUAGES = Set.of("English", "Vietnamese");
    private static final String DEFAULT_LAYOUT_COLOR = "current-blue";
    private static final Set<String> LAYOUT_COLORS = Set.of("current-blue", "navy", "teal", "green", "purple", "orange");

    private final SystemSettingsRepository repository;

    public SystemSettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    public SystemSettingsResponse get() {
        return toResponse(getOrCreate());
    }

    public SystemSettingsResponse update(SystemSettingsRequest request) {
        if (request == null) throw new MasterDataValidationException("Settings payload is required");
        SystemSettings settings = getOrCreate();
        settings.setCompanyName(required(request.companyName(), "Company/System Name is required"));
        settings.setTimeZone(required(request.timeZone(), "Time Zone is required"));

        String dateFormat = required(request.dateFormat(), "Date Format is required").toUpperCase(Locale.ROOT);
        if (!DATE_FORMATS.contains(dateFormat)) throw new MasterDataValidationException("Unsupported Date Format");
        settings.setDateFormat(dateFormat);

        String numberFormat = required(request.numberFormat(), "Number Format is required");
        if (!NUMBER_FORMATS.contains(numberFormat)) throw new MasterDataValidationException("Unsupported Number Format");
        settings.setNumberFormat(numberFormat);

        int decimals = request.decimalPlaces() == null ? 4 : request.decimalPlaces();
        if (decimals < 0 || decimals > 8) throw new MasterDataValidationException("Decimal Places must be between 0 and 8");
        settings.setDecimalPlaces(decimals);

        String language = required(request.defaultLanguage(), "Default Language is required");
        if (!LANGUAGES.contains(language)) throw new MasterDataValidationException("Unsupported Default Language");
        settings.setDefaultLanguage(language);

        touch(settings);
        return toResponse(repository.save(settings));
    }

    public SystemSettingsResponse updateLayoutColor(String layoutColor) {
        String value = required(layoutColor, "Layout Color is required").toLowerCase(Locale.ROOT);
        if (!LAYOUT_COLORS.contains(value)) throw new MasterDataValidationException("Unsupported Layout Color");
        SystemSettings settings = getOrCreate();
        settings.setLayoutColor(value);
        touch(settings);
        return toResponse(repository.save(settings));
    }

    public SystemSettingsResponse updateLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new MasterDataValidationException("Logo file is required");
        if (file.getSize() > MAX_LOGO_BYTES) throw new MasterDataValidationException("Logo file must be 2 MB or smaller");
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) throw new MasterDataValidationException("Logo must be an image file");
        if (!Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml").contains(contentType)) {
            throw new MasterDataValidationException("Supported logo formats: PNG, JPG, WEBP, GIF or SVG");
        }

        SystemSettings settings = getOrCreate();
        try {
            settings.setLogoData(file.getBytes());
        } catch (IOException ex) {
            throw new MasterDataValidationException("Unable to read logo file");
        }
        settings.setLogoFileName(cleanFileName(file.getOriginalFilename()));
        settings.setLogoContentType(contentType);
        touch(settings);
        return toResponse(repository.save(settings));
    }

    public SystemSettingsResponse deleteLogo() {
        SystemSettings settings = getOrCreate();
        settings.setLogoData(null);
        settings.setLogoFileName(null);
        settings.setLogoContentType(null);
        touch(settings);
        return toResponse(repository.save(settings));
    }

    public LogoPayload getLogo() {
        SystemSettings settings = getOrCreate();
        byte[] data = settings.getLogoData();
        if (data == null || data.length == 0) return null;
        return new LogoPayload(
                data,
                hasText(settings.getLogoContentType()) ? settings.getLogoContentType() : "application/octet-stream",
                hasText(settings.getLogoFileName()) ? settings.getLogoFileName() : "company-logo"
        );
    }

    private SystemSettings getOrCreate() {
        return repository.findById(SystemSettings.GENERAL_ID).orElseGet(() -> {
            SystemSettings settings = new SystemSettings();
            settings.setId(SystemSettings.GENERAL_ID);
            settings.setCompanyName("Youngone MPR System");
            settings.setTimeZone("Asia/Ho_Chi_Minh");
            settings.setDateFormat("DD/MM/YYYY");
            settings.setNumberFormat("1,234.56");
            settings.setDecimalPlaces(4);
            settings.setDefaultLanguage("English");
            settings.setLayoutColor(DEFAULT_LAYOUT_COLOR);
            settings.setUpdatedBy("system");
            settings.setUpdatedAt(LocalDateTime.now());
            return repository.save(settings);
        });
    }

    private void touch(SystemSettings settings) {
        settings.setId(SystemSettings.GENERAL_ID);
        settings.setUpdatedBy(RequestActor.current());
        settings.setUpdatedAt(LocalDateTime.now());
    }

    private SystemSettingsResponse toResponse(SystemSettings settings) {
        boolean hasLogo = settings.getLogoData() != null && settings.getLogoData().length > 0;
        return new SystemSettingsResponse(
                settings.getCompanyName(),
                settings.getTimeZone(),
                settings.getDateFormat(),
                settings.getNumberFormat(),
                settings.getDecimalPlaces(),
                settings.getDefaultLanguage(),
                normalizeLayoutColor(settings.getLayoutColor()),
                hasLogo,
                settings.getLogoFileName(),
                settings.getLogoContentType(),
                hasLogo ? "/api/system-settings/logo" : null,
                settings.getUpdatedBy(),
                settings.getUpdatedAt()
        );
    }

    private String normalizeLayoutColor(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return LAYOUT_COLORS.contains(clean) ? clean : DEFAULT_LAYOUT_COLOR;
    }

    private String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new MasterDataValidationException(message);
        return clean;
    }

    private String cleanFileName(String value) {
        String name = value == null || value.isBlank() ? "company-logo" : value.trim();
        return name.replaceAll("[\\\\/\\r\\n]", "_");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record LogoPayload(byte[] data, String contentType, String fileName) { }
}
