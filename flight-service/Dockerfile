FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy parent pom
COPY pom.xml .

# Copy shared-proto module
COPY shared-proto/pom.xml shared-proto/pom.xml
COPY shared-proto/src shared-proto/src

# Copy flight-service module
COPY flight-service/pom.xml flight-service/pom.xml
COPY flight-service/src flight-service/src

# Provide a stub pom for booking-service so parent POM validation succeeds
COPY booking-service/pom.xml booking-service/pom.xml

# Build (skip tests for Docker build)
RUN mvn -pl shared-proto,flight-service -am clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/flight-service/target/flight-service-1.0.0.jar app.jar

EXPOSE 8081 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
