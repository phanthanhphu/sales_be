# ===== BUILD STAGE =====
FROM gradle:8.7-jdk17 AS build

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

RUN ./gradlew dependencies --no-daemon || true

COPY src src

RUN ./gradlew clean bootJar -x test --no-daemon


# ===== RUN STAGE =====
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/build-local/libs/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]