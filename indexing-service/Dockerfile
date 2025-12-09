FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY ../../pom.xml .
COPY ../../core-lib/pom.xml core-lib/
COPY ../../ingestion-service/pom.xml ingestion-service/
COPY ../../indexing-service/pom.xml indexing-service/
COPY ../../search-service/pom.xml search-service/

COPY ../../core-lib/src core-lib/src
COPY ../../ingestion-service/src ingestion-service/src
COPY ../../indexing-service/src indexing-service/src
COPY ../../search-service/src search-service/src

RUN mvn install -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/indexing-service/target/indexing-service-1.0.0.jar ./app.jar
COPY ../../hazelcast.xml .
CMD ["java", "-jar", "app.jar"]