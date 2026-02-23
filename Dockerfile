# Stage 1: Build the application with Maven (Java 17 to match pom.xml)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom first and download dependencies so they are cached when only source changes
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and package the application (skip tests — CI already ran them)
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime image — JRE only, no Maven or JDK
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built JAR from the build stage and rename for a fixed entrypoint
COPY --from=build /app/target/*.jar app.jar

# Expose the port the app listens on (Railway overrides with PORT at runtime)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
