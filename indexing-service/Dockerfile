FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY ../../pom.xml .
COPY ../../indexing-service/pom.xml indexing-service/
COPY ../../indexing-service/src indexing-service/src

RUN mvn -f indexing-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/indexing-service/target/indexing-service-1.0.0.jar ./app.jar
CMD ["java", "-jar", "app.jar"]