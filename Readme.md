# Food Delivery Backend

## Overview
This is a comprehensive microservices-based backend for a food delivery platform.
It is built using **Java 17**, **Spring Boot 3.2.0**, and **Spring Cloud**.

## 🚀 Quick Start
Refer to [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md) for the detailed current status and "Next Steps".

### Prerequisites
* Java 17
* Maven 3.8+
* Docker & Docker Compose

### Running the Services
See `helper.md` for specific docker commands.
Generally:
```bash
docker compose up -d
mvn spring-boot:run -pl discovery-service
```

## 📂 Project Structure
* **Core Services**: Order, Restaurant, User, Payment, Delivery.
* **Infrastructure**: API Gateway, Discovery Service.
* **Libraries**: Commons.

For a detailed breakdown of where we are in the development lifecycle, please read **[PROJECT_PROGRESS.md](PROJECT_PROGRESS.md)**.
