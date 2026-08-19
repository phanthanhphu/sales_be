package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.dto.carton.ScaleStationRequest;
import org.bsl.cartonloading.dto.carton.StationHeartbeatRequest;
import org.bsl.cartonloading.model.ScaleStation;
import org.bsl.cartonloading.service.ScaleStationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carton-loading/stations")
public class ScaleStationController {
    private final ScaleStationService service;

    public ScaleStationController(ScaleStationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ScaleStation> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(activeOnly);
    }

    @GetMapping("/{stationCode}")
    public ScaleStation get(@PathVariable String stationCode) {
        return service.get(stationCode);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PostMapping
    public ScaleStation create(@RequestBody ScaleStationRequest request) {
        return service.create(request);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping("/{stationCode}")
    public ScaleStation update(@PathVariable String stationCode, @RequestBody ScaleStationRequest request) {
        return service.update(stationCode, request);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @PostMapping("/{stationCode}/heartbeat")
    public ScaleStation heartbeat(@PathVariable String stationCode, @RequestBody StationHeartbeatRequest request) {
        return service.heartbeat(stationCode, request);
    }
}
