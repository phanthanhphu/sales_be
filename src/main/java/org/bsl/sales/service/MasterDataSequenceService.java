package org.bsl.sales.service;

import org.bsl.sales.model.MasterDataSequence;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Atomic, process-safe sequence allocator for VC/MI/ST master keys. */
@Service
public class MasterDataSequenceService {
    private final MongoTemplate mongoTemplate;
    private final Set<String> initialized = ConcurrentHashMap.newKeySet();

    public MasterDataSequenceService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String next(String sequenceName, String prefix, LongSupplier existingMaxSupplier) {
        return reserve(sequenceName, prefix, 1, existingMaxSupplier).get(0);
    }

    public List<String> reserve(String sequenceName, String prefix, int count, LongSupplier existingMaxSupplier) {
        if (count <= 0) return List.of();
        initialize(sequenceName, existingMaxSupplier);
        Query query = Query.query(Criteria.where("_id").is(sequenceName));
        MasterDataSequence updated = mongoTemplate.findAndModify(
                query,
                new Update().inc("value", count),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                MasterDataSequence.class
        );
        long end = updated == null ? count : updated.getValue();
        long start = end - count + 1;
        List<String> keys = new ArrayList<>(count);
        for (long value = start; value <= end; value++) {
            keys.add(prefix + String.format("%06d", value));
        }
        return keys;
    }

    private void initialize(String sequenceName, LongSupplier existingMaxSupplier) {
        if (!initialized.add(sequenceName)) return;
        long existingMax = Math.max(0L, existingMaxSupplier == null ? 0L : existingMaxSupplier.getAsLong());
        Query query = Query.query(Criteria.where("_id").is(sequenceName));
        mongoTemplate.upsert(query, new Update().max("value", existingMax), MasterDataSequence.class);
    }
}
