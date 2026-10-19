# Framework baseline: January–March 2024 compatible

The shared `pom.xml` owns the dependency-management versions across **all 12 Maven modules**.

| Component | Pinned version | Historical availability |
|---|---|---|
| Java | 17 (LTS) | September 2021 |
| Spring Boot | 3.2.2 | January 19, 2024 |
| Spring Cloud | 2023.0.0 (Leyton) | December 6, 2023 |

Boot `3.2.2` is managed by the Spring Boot BOM and by the Spring Boot Maven plugin in the root POM.
All microservice POMs inherit the root parent, so do not specify local Spring Boot starter versions.

Spring's official [3.2.2 release announcement](https://spring.io/blog/2024/01/19/spring-boot-3-2-2-available-now/) and [Spring Cloud 2023.0.0 announcement](https://spring.io/blog/2023/12/06/spring-cloud-2023-0-0-aka-leyton-is-now-available/) document release timing.

To verify the version and compile every module using Java 17:

```bash
mvn -B -ntp -q help:evaluate -Dexpression=spring.boot.version -DforceStdout
mvn -B -ntp -DskipTests package
```

The workflow `.github/workflows/spring-boot-reactor.yml` additionally checks that each individual module inherits exactly `3.2.2`.

This is a **historically available** baseline, **not a claim that Spring Boot 3.2.2 is still security-supported today**. For deployments exposed to untrusted traffic in 2026, evaluate supported patch/line upgrades and all transitive dependencies. Actual project history must be represented accurately.
