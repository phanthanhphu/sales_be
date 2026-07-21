package org.bsl.sales.config;

import jakarta.annotation.PostConstruct;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.ProductColorMaster;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.model.User;
import org.bsl.sales.support.BuyerKeys;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;

@Configuration
public class BuyerDataMigrationConfig {
    private final MongoTemplate mongoTemplate;

    public BuyerDataMigrationConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void migrateBuyerOwnership() {
        backfillBuyerKey(SalesOrder.class);
        backfillBuyerKey(BomDocument.class);
        backfillBuyerKey(MprDocument.class);
        backfillBuyerKey(MatInfo.class);
        backfillBuyerKey(ProductColorMaster.class);

        Query legacyUsers = new Query(new Criteria().orOperator(
                Criteria.where("buyerKeys").exists(false),
                Criteria.where("buyerKeys").is(null)
        ));
        mongoTemplate.updateMulti(legacyUsers, new Update().set("buyerKeys", List.of(BuyerKeys.LL_BEAN)), User.class);

        recreateBuyerIndexes();
    }

    private void backfillBuyerKey(Class<?> entityClass) {
        Query query = new Query(new Criteria().orOperator(
                Criteria.where("buyerKey").exists(false),
                Criteria.where("buyerKey").is(null),
                Criteria.where("buyerKey").is("")
        ));
        mongoTemplate.updateMulti(query, new Update().set("buyerKey", BuyerKeys.LL_BEAN), entityClass);
    }

    private void recreateBuyerIndexes() {
        IndexOperations orders = mongoTemplate.indexOps(SalesOrder.class);
        dropQuietly(orders, "orderNoKey");
        dropQuietly(orders, "orderNoKey_1");
        orders.ensureIndex(new Index()
                .on("buyerKey", Sort.Direction.ASC)
                .on("orderNoKey", Sort.Direction.ASC)
                .unique()
                .named("uk_order_buyer_no"));

        IndexOperations matInfo = mongoTemplate.indexOps(MatInfo.class);
        dropQuietly(matInfo, "checkingKey");
        dropQuietly(matInfo, "checkingKey_1");
        matInfo.ensureIndex(new Index()
                .on("buyerKey", Sort.Direction.ASC)
                .on("checkingKey", Sort.Direction.ASC)
                .unique()
                .named("uk_mat_info_buyer_identity"));

        IndexOperations productColor = mongoTemplate.indexOps(ProductColorMaster.class);
        dropQuietly(productColor, "masterKey");
        dropQuietly(productColor, "masterKey_1");
        productColor.ensureIndex(new Index()
                .on("buyerKey", Sort.Direction.ASC)
                .on("masterKey", Sort.Direction.ASC)
                .unique()
                .named("uk_product_color_buyer_key"));
    }

    private void dropQuietly(IndexOperations operations, String name) {
        try {
            operations.dropIndex(name);
        } catch (RuntimeException ignored) {
            // Existing deployments may use a different generated index name.
        }
    }
}
