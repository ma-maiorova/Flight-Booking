FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy parent pom
COPY pom.xml .

# Copy shared-proto module
COPY shared-proto/pom.xml shared-proto/pom.xml
COPY shared-proto/src shared-proto/src

# Copy booking-service module
COPY booking-service/pom.xml booking-service/pom.xml
COPY booking-service/src booking-service/src

# Provide a stub pom for flight-service so parent POM validation succeeds
COPY flight-service/pom.xml flight-service/pom.xml

# Build (skip tests)
RUN mvn -pl shared-proto,booking-service -am clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/booking-service/target/booking-service-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
