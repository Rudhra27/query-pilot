# syntax=docker/dockerfile:1

# --- build ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --version --no-daemon

COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# --- run ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 1001 appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
