# ============================================================
# BUILD STAGE
# ============================================================

FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom first for better Docker layer caching
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build Spring Boot application
RUN mvn clean package -DskipTests


# ============================================================
# RUNTIME STAGE
# ============================================================

FROM eclipse-temurin:21-jre

WORKDIR /app

# Render uses the PORT environment variable.
# Spring Boot will bind to this port through SERVER_PORT.
ENV SERVER_PORT=10000

# Copy built jar from builder
COPY --from=builder /app/target/*.jar app.jar

# Render web services use port 10000 by default.
EXPOSE 10000

# Start Spring Boot
ENTRYPOINT ["java", "-jar", "app.jar"]