# Build context: repository root (multi-module Maven build).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source changes don't re-download them.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY hookguard-service/pom.xml hookguard-service/
COPY demo-receiver/pom.xml demo-receiver/
RUN ./mvnw -q -B -pl hookguard-service -am dependency:go-offline

COPY hookguard-service/src hookguard-service/src
RUN ./mvnw -q -B -pl hookguard-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hookguard && adduser -S hookguard -G hookguard
WORKDIR /app
COPY --from=build /workspace/hookguard-service/target/hookguard-service-*.jar app.jar
USER hookguard
# 8080 = API profile, 8081 = worker profile (actuator only)
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
