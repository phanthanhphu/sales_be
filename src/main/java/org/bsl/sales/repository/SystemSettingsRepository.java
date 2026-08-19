package org.bsl.sales.repository;

import org.bsl.sales.model.SystemSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SystemSettingsRepository extends MongoRepository<SystemSettings, String> {
}
