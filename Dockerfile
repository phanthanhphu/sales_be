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

# Tìm file jar thực tế và copy về đường dẫn cố định
RUN JAR_FILE="$(find /app -type f -path '*/build/libs/*.jar' \
    ! -name '*-plain.jar' | head -n 1)" \
    && echo "JAR found: $JAR_FILE" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /app/app.jar


# ===== RUN STAGE =====
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/app.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]