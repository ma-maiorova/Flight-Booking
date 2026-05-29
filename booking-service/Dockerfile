FROM maven:3.9-eclipse-temurin-25 AS build
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

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y wget --no-install-recommends && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=build /app/booking-service/target/booking-service-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
