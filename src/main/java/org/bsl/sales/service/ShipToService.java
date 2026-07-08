package org.bsl.sales.service;

import org.bsl.sales.dto.ShipToRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.model.ShipTo;
import org.bsl.sales.repository.ShipToRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ShipToService {
    private final ShipToRepository repository;

    public ShipToService(ShipToRepository repository) {
        this.repository = repository;
    }

    public ShipTo create(ShipToRequest request) {
        String name = required(request == null ? null : request.shipToName(), "Ship To name is required");
        String key = normalize(name);
        if (repository.findByShipToNameKey(key).isPresent()) {
            throw new MasterDataConflictException("Ship To already exists: " + name);
        }
        LocalDateTime now = LocalDateTime.now();
        ShipTo entity = new ShipTo();
        entity.setShipToNameKey(key);
        entity.setShipToName(name);
        entity.setShipToCode(trim(request.shipToCode()));
        entity.setActive(request.active() == null || request.active());
        entity.setRemark(trim(request.remark()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    public Page<ShipTo> list(String shipToName, String shipToCode, Boolean active, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        String name = normalize(shipToName);
        String code = normalize(shipToCode);
        Page<ShipTo> result;
        if (name.isEmpty() && code.isEmpty()) {
            result = repository.findAll(pageable);
        } else {
            result = repository.findByShipToNameKeyContainingOrShipToCodeContainingOrShipToNameContaining(
                    name, code, trim(shipToName), pageable
            );
        }
        if (active == null) return result;
        List<ShipTo> filtered = result.getContent().stream()
                .filter(item -> item.isActive() == active)
                .toList();
        return new PageImpl<>(filtered, pageable, filtered.size());
    }

    public List<ShipTo> listActive() {
        return repository.findByActiveTrueOrderByShipToNameAsc();
    }

    public ShipTo get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Ship To not found"));
    }

    public ShipTo update(String id, ShipToRequest request) {
        ShipTo entity = get(id);
        String name = required(request == null ? null : request.shipToName(), "Ship To name is required");
        String key = normalize(name);
        repository.findByShipToNameKey(key)
                .filter(other -> !other.getId().equals(entity.getId()))
                .ifPresent(other -> { throw new MasterDataConflictException("Ship To already exists: " + name); });

        entity.setShipToNameKey(key);
        entity.setShipToName(name);
        entity.setShipToCode(trim(request.shipToCode()));
        entity.setActive(request.active() == null || request.active());
        entity.setRemark(trim(request.remark()));
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public void delete(String id) {
        repository.delete(get(id));
    }

    private String required(String value, String message) {
        String result = trim(value);
        if (result.isEmpty()) throw new IllegalArgumentException(message);
        return result;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        return trim(value).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
