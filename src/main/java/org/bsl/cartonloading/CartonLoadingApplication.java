package org.bsl.cartonloading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableCaching // 🔥 BẬT CACHING
@EnableMongoRepositories // 🔥 MONGODB REPOSITORIES
public class CartonLoadingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartonLoadingApplication.class, args);
    }

}
