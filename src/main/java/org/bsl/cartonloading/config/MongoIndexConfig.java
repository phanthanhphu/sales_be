// src/main/java/org/bsl/pricecomparison/config/MongoIndexConfig.java
package org.bsl.cartonloading.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import jakarta.annotation.PostConstruct;

@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void createIndexes() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("requisition_monthly");

        // Drop old index if exists
        try {
            collection.dropIndex("departmentRequisitions.name_1");
            System.out.println("Dropped legacy index: departmentRequisitions.name_1");
        } catch (Exception e) {
            // Ignore if index doesn't exist
        }

        // Create essential single-field indexes
        collection.createIndex(Indexes.ascending("groupId"));
        collection.createIndex(Indexes.ascending("type"));
        collection.createIndex(Indexes.descending("updatedDate"));
        collection.createIndex(Indexes.descending("createdDate"));
        collection.createIndex(Indexes.ascending("oldSAPCode"));
        collection.createIndex(Indexes.ascending("productType1Name"));
        collection.createIndex(Indexes.ascending("productType2Name"));

        // Create partial index on nested field with explicit name
        collection.createIndex(
                Indexes.ascending("departmentRequisitions.name"),
                new IndexOptions()
                        .name("idx_department_name")
                        .partialFilterExpression(new Document("departmentRequisitions.name", new Document("$exists", true)))
        );

        MongoCollection<Document> buyers = mongoTemplate.getCollection("buyers");
        buyers.createIndex(
                Indexes.ascending("buyerKey"),
                new IndexOptions().name("uq_buyer_key").unique(true)
        );
        buyers.createIndex(
                Indexes.ascending("slug"),
                new IndexOptions().name("uq_buyer_slug").unique(true)
        );
        buyers.createIndex(Indexes.compoundIndex(Indexes.ascending("active"), Indexes.ascending("sequence")));

        MongoCollection<Document> packingOrders = mongoTemplate.getCollection("packing_orders");
        try {
            packingOrders.dropIndex("uq_packing_order_buyer_order_no");
            System.out.println("Dropped legacy packing order number index");
        } catch (Exception ignored) {
            // The legacy index may not exist on a new database.
        }
        packingOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("orderDate")),
                new IndexOptions().name("idx_packing_order_buyer_date")
        );
        packingOrders.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("updatedAt")));

        MongoCollection<Document> allocationLines = mongoTemplate.getCollection("packing_allocation_lines");
        allocationLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_allocation_order_line")
        );
        allocationLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")));
        allocationLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")));

        MongoCollection<Document> packingListLines = mongoTemplate.getCollection("packing_list_lines");
        packingListLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_packing_list_order_line")
        );
        packingListLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")));
        packingListLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")));

        MongoCollection<Document> scaleStations = mongoTemplate.getCollection("scale_stations");
        scaleStations.createIndex(
                Indexes.ascending("stationCode"),
                new IndexOptions().name("uq_scale_station_code").unique(true)
        );
        scaleStations.createIndex(Indexes.ascending("active"));

        MongoCollection<Document> cartonTransactions = mongoTemplate.getCollection("carton_scan_transactions");
        // Migrate legacy non-partial unique indexes so PLANNED cartons may keep jobId/packingLineId null.
        for (String indexName : new String[]{"uq_carton_job_id", "uq_carton_line_sequence", "uq_wsp_master_carton_sequence"}) {
            try {
                cartonTransactions.dropIndex(indexName);
            } catch (Exception ignored) {
                // New database or index already migrated.
            }
        }
        cartonTransactions.createIndex(
                Indexes.ascending("jobId"),
                new IndexOptions()
                        .name("uq_carton_job_id")
                        .unique(true)
                        .partialFilterExpression(new Document("jobId", new Document("$type", "number")))
        );
        cartonTransactions.createIndex(
                Indexes.ascending("stationCode"),
                new IndexOptions()
                        .name("uq_station_waiting_job")
                        .unique(true)
                        .partialFilterExpression(new Document("status", "WAITING_WEIGHT"))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("scannedAt")),
                new IndexOptions().name("idx_carton_order_scanned")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_line_status")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions()
                        .name("uq_carton_line_sequence")
                        .unique(true)
                        .partialFilterExpression(new Document("packingLineId", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("masterLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions()
                        .name("uq_wsp_master_carton_sequence")
                        .unique(true)
                        .partialFilterExpression(new Document("masterLineId", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("orderCartonSequence")),
                new IndexOptions().name("idx_carton_order_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("factoryBarcode"),
                        Indexes.ascending("orderCartonSequence")
                ),
                new IndexOptions().name("idx_carton_assignment_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("itemKey")),
                new IndexOptions()
                        .name("uq_carton_item_key")
                        .unique(true)
                        .partialFilterExpression(new Document("itemKey", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber"), Indexes.ascending("articleNumber"), Indexes.ascending("itemSequence")),
                new IndexOptions().name("idx_carton_business_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.ascending("factoryBarcode"),
                new IndexOptions()
                        .name("uq_carton_factory_barcode")
                        .unique(true)
                        .partialFilterExpression(new Document("factoryBarcode", new Document("$type", "string")))
        );

        MongoCollection<Document> factoryBarcodes = mongoTemplate.getCollection("factory_barcodes");
        factoryBarcodes.createIndex(
                Indexes.ascending("barcode"),
                new IndexOptions().name("uq_factory_barcode").unique(true)
        );
        factoryBarcodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("year"), Indexes.ascending("factoryCode"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("uq_factory_barcode_sequence").unique(true)
        );
        factoryBarcodes.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")));
        factoryBarcodes.createIndex(Indexes.compoundIndex(Indexes.ascending("batchId"), Indexes.ascending("runningNumber")));
        factoryBarcodes.createIndex(
                Indexes.ascending("assignedCartonId"),
                new IndexOptions()
                        .name("uq_factory_barcode_assigned_carton")
                        .unique(true)
                        .partialFilterExpression(new Document("assignedCartonId", new Document("$type", "string")))
        );

        System.out.println("All indexes created successfully using Indexes class 100% operational!");
    }
}