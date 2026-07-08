package org.bsl.sales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableCaching // 🔥 BẬT CACHING
@EnableMongoRepositories // 🔥 MONGODB REPOSITORIES
public class PriceComparisonApplication {

    public static void main(String[] args) {
        SpringApplication.run(PriceComparisonApplication.class, args);
    }

}
