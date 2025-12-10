FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY ../../pom.xml .
COPY ../../ingestion-service/pom.xml ingestion-service/
COPY ../../ingestion-service/src ingestion-service/src

RUN mvn -f ingestion-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/ingestion-service/target/ingestion-service-1.0.0.jar ./app.jar
EXPOSE 7001
CMD ["java", "-jar", "app.jar"]