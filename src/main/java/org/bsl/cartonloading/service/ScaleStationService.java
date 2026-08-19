package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.carton.ScaleStationRequest;
import org.bsl.cartonloading.dto.carton.StationHeartbeatRequest;
import org.bsl.cartonloading.model.ScaleStation;
import org.bsl.cartonloading.repository.ScaleStationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ScaleStationService {
    private static final BigDecimal DEFAULT_MINIMUM_WEIGHT = new BigDecimal("0.50");
    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.02");

    private final ScaleStationRepository repository;

    public ScaleStationService(ScaleStationRepository repository) {
        this.repository = repository;
    }

    public List<ScaleStation> list(boolean activeOnly) {
        return activeOnly ? repository.findByActiveTrueOrderByStationCodeAsc() : repository.findAllByOrderByStationCodeAsc();
    }

    public ScaleStation get(String stationCode) {
        return repository.findByStationCode(normalizeCode(stationCode))
                .orElseThrow(() -> new IllegalArgumentException("Scale station not found: " + stationCode));
    }

    public ScaleStation requireActive(String stationCode) {
        ScaleStation station = get(stationCode);
        if (!station.isActive()) throw new IllegalArgumentException("Scale station is inactive: " + station.getStationCode());
        return station;
    }

    public ScaleStation create(ScaleStationRequest request) {
        String code = normalizeCode(request.stationCode());
        if (repository.existsByStationCode(code)) {
            throw new IllegalArgumentException("Scale station code already exists: " + code);
        }
        LocalDateTime now = LocalDateTime.now();
        ScaleStation station = new ScaleStation();
        station.setStationCode(code);
        apply(station, request);
        station.setOnline(false);
        station.setCreatedAt(now);
        station.setUpdatedAt(now);
        station.setCreatedBy(RequestActor.current());
        station.setUpdatedBy(RequestActor.current());
        return repository.save(station);
    }

    public ScaleStation update(String stationCode, ScaleStationRequest request) {
        ScaleStation station = get(stationCode);
        String requestedCode = normalizeCode(request.stationCode());
        if (!station.getStationCode().equals(requestedCode) && repository.existsByStationCode(requestedCode)) {
            throw new IllegalArgumentException("Scale station code already exists: " + requestedCode);
        }
        station.setStationCode(requestedCode);
        apply(station, request);
        station.setUpdatedAt(LocalDateTime.now());
        station.setUpdatedBy(RequestActor.current());
        return repository.save(station);
    }

    public ScaleStation heartbeat(String stationCode, StationHeartbeatRequest request) {
        ScaleStation station = get(stationCode);
        station.setOnline(request.online() == null || request.online());
        station.setStatusMessage(clean(request.message()));
        station.setLastHeartbeatAt(LocalDateTime.now());
        station.setUpdatedAt(LocalDateTime.now());
        station.setUpdatedBy("PLC_GATEWAY");
        return repository.save(station);
    }

    public ScaleStation markOnline(String stationCode, String message) {
        return heartbeat(stationCode, new StationHeartbeatRequest(true, message));
    }

    private void apply(ScaleStation station, ScaleStationRequest request) {
        station.setStationName(required(request.stationName(), "Station name is required"));
        station.setPlcIp(clean(request.plcIp()));
        station.setGatewayIp(clean(request.gatewayIp()));
        station.setLocation(clean(request.location()));
        station.setActive(request.active() == null || request.active());
        station.setMinimumWeightKg(positiveOrDefault(request.minimumWeightKg(), DEFAULT_MINIMUM_WEIGHT));
        station.setStabilityToleranceKg(positiveOrDefault(request.stabilityToleranceKg(), DEFAULT_TOLERANCE));
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private String normalizeCode(String value) {
        String clean = required(value, "Station code is required")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (clean.isBlank()) throw new IllegalArgumentException("Station code is required");
        return clean;
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new IllegalArgumentException(message);
        return clean;
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }
}
