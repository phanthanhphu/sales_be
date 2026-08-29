package org.bsl.sales.repository;

import org.bsl.sales.model.ProductColorMaster;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Legacy read bridge used only to migrate old Product Color Master data into each BOM. */
public interface ProductColorMasterRepository extends MongoRepository<ProductColorMaster, String> {
}
