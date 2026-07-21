package org.bsl.sales.config;

import org.bsl.sales.model.AuditLog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time-compatible cleanup that is safe to run at every startup.
 * It converts useful historical mutation logs to ADD/EDIT/DELETE, removes
 * read-only history, and removes the no-longer-used userAgent field.
 */
@Component
public class AuditLogDataCleanup implements ApplicationRunner {
    private static final List<String> ADD_ACTIONS = List.of(
            "CREATE", "UPLOAD_EXCEL", "GENERATE"
    );
    private static final List<String> EDIT_ACTIONS = List.of(
            "UPDATE", "UPLOAD_EDITED_EXCEL", "REPLACE_EXCEL", "UPLOAD_IMAGE",
            "SUBMIT", "APPLY", "RECHECK", "RESOLVE", "CHANGE_PASSWORD", "RESET_PASSWORD"
    );
    private static final List<String> DELETE_ACTIONS = List.of("DELETE_IMAGE");
    private static final List<String> ALLOWED_ACTIONS = List.of("ADD", "EDIT", "DELETE");

    private final MongoTemplate mongoTemplate;

    public AuditLogDataCleanup(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateActions(ADD_ACTIONS, "ADD");
        migrateActions(EDIT_ACTIONS, "EDIT");
        migrateActions(DELETE_ACTIONS, "DELETE");

        mongoTemplate.remove(
                Query.query(Criteria.where("action").nin(ALLOWED_ACTIONS)),
                AuditLog.class
        );
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("userAgent").exists(true)),
                new Update().unset("userAgent"),
                AuditLog.class
        );
    }

    private void migrateActions(List<String> oldActions, String newAction) {
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("action").in(oldActions)),
                Update.update("action", newAction),
                AuditLog.class
        );
    }
}
