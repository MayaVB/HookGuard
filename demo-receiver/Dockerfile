# Build context: repository root (multi-module Maven build).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY hookguard-service/pom.xml hookguard-service/
COPY demo-receiver/pom.xml demo-receiver/
RUN ./mvnw -q -B -pl demo-receiver -am dependency:go-offline

COPY demo-receiver/src demo-receiver/src
RUN ./mvnw -q -B -pl demo-receiver -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S receiver && adduser -S receiver -G receiver
WORKDIR /app
COPY --from=build /workspace/demo-receiver/target/demo-receiver-*.jar app.jar
USER receiver
EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
