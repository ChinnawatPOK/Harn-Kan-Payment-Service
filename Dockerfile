FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/payment-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8082 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
