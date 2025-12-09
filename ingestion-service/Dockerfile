FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 1. Copy the Parent POM (needed for version/dependency resolution)
COPY pom.xml .

# 2. Copy the Service POM and Source Code
# Note: We are copying from "ingestion-service/..." because context is root
COPY ingestion-service/pom.xml ingestion-service/
COPY ingestion-service/src ingestion-service/src

# 3. Build using the specific POM
# The -f flag tells Maven to build this specific child project
RUN mvn -f ingestion-service/pom.xml clean package -DskipTests

# --- Runtime Stage ---
FROM eclipse-temurin:17-jdk
WORKDIR /app
# Note: The path to the jar includes the module folder now
COPY --from=build /app/ingestion-service/target/ingestion-service-1.0.0.jar ./app.jar
EXPOSE 7001
CMD ["java", "-jar", "app.jar"]