# ==============================================================================
# MASTER DOCKERFILE FOR MULTI-MODULE SPRING BOOT APPS
# ==============================================================================
# This Dockerfile is designed to build *any* of your services by passing
# a build argument (MODULE_NAME).
#
# Usage: docker build --build-arg MODULE_NAME=order-service -t order-service .
# ==============================================================================

# ------------------------------------------------------------------------------
# STAGE 1: BUILD LAYER
# ------------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# 1. Copy the entire project source code
# (We copy everything so the reactor knows about parent/child relationships)
COPY . .

# 2. Define the build argument (passed from docker-compose)
ARG SERVICE_NAME

# 3. Build the specific module
# -pl ${MODULE_NAME} : Build only this project (e.g., user-service)
# -am                : Also make dependents (builds commons-service automatically)
# -DskipTests        : Skip tests to speed up container creation
RUN mvn clean package -pl ${SERVICE_NAME} -am -DskipTests

# ------------------------------------------------------------------------------
# STAGE 2: RUNTIME LAYER
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Define the argument again (ARGS are not persisted between stages)
ARG SERVICE_NAME

# 1. Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 2. Copy the compiled JAR from the builder stage
# We use a wildcard (*.jar) so we don't have to know the specific version (1.0.0 vs 0.0.1)
COPY --from=builder /app/${SERVICE_NAME}/target/*.jar app.jar

# 3. Expose port 8080 (Standard Spring Boot Port)
EXPOSE 8080

# 4. Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]