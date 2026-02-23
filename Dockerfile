# Stage 1: Build the application with Maven (Java 17 to match pom.xml)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom first and download dependencies so they are cached when only source changes
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and package the application (skip tests — CI already ran them)
COPY src ./src
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────
# Use Ubuntu-based JRE so we can easily install Node.js + Python
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install Node.js 20 and Python3
# apt-get update fetches the package list
# nodejs and python3 are needed by ScriptRunner.java to execute
# JavaScript and Python scripts at runtime
# curl is needed to add the NodeSource repository for Node 20
RUN apt-get update && \
    apt-get install -y curl python3 && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Verify both runtimes are available (printed in Railway build logs)
RUN node --version && python3 --version

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
