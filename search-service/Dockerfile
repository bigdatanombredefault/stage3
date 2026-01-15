FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY ../../pom.xml .
COPY ../../search-service/pom.xml search-service/
COPY ../../search-service/src search-service/src

RUN mvn -f search-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/search-service/target/search-service-1.0.0.jar ./app.jar
EXPOSE 7003
CMD ["java", "-jar", "app.jar"]