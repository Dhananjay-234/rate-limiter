# Multi-stage build. The MODULE arg selects which Spring Boot module to package,
# so all three services share one Dockerfile.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy poms first so dependency resolution is cached independently of source changes.
COPY pom.xml .
COPY rate-limiter-core/pom.xml rate-limiter-core/
COPY api-gateway/pom.xml api-gateway/
COPY service-a/pom.xml service-a/
COPY service-b/pom.xml service-b/
RUN mvn -q -B dependency:go-offline -DskipTests || true

COPY rate-limiter-core rate-limiter-core
COPY api-gateway api-gateway
COPY service-a service-a
COPY service-b service-b

ARG MODULE
RUN mvn -q -B clean package -DskipTests -pl ${MODULE} -am

FROM eclipse-temurin:21-jre-alpine

ARG MODULE
WORKDIR /app

RUN apk add --no-cache wget

COPY --from=build /build/${MODULE}/target/*.jar app.jar

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
