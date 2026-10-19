# User Service — implementation and operations

Java 17 + Spring Boot 3.2 + Spring Security 6 + MySQL 8 (schema already has users, refresh tokens, login attempts, preferences, and POINT-based addresses).

Implemented:
- Registration with normalized email, unique E.164 phone and BCrypt(12). Passwords must have 12+ characters and fit BCrypt 72 UTF-8 byte limit.
- Login with generic credential errors, distributed MySQL-backed lockout counter (5 failed attempts / 15 minutes per email). This is NOT a substitute for IP/device rate limits at ingress.
- HS256 JWT bearer access tokens (15 minutes), issuer/audience/expiry validation, and DB-backed tokenVersion/account-active checks at each authenticated request.
- Refresh tokens: random opaque tokens stored only as SHA-256 hashes; row-locked rotation; logout/logout-all; password change revocation.
- SMTP email verification, one-use password reset links, account deactivation, self-profile update.
- User-scoped CRUD saved addresses (MySQL SRID 4326; serialized default address changes) and preference GET/PUT with consent flags.
- Internal user lookup requires a private 32+ byte X-Internal-Api-Key; the matching Order Service Feign client now uses this header.
- Additive Flyway migrations, actuator health endpoints, no exception traces to clients.

Local setup:
1. Start MySQL and Eureka.
2. Set environment variables USER_JWT_SECRET (32+ unpredictable UTF-8 bytes) and USER_INTERNAL_API_KEY (32+ unpredictable UTF-8 bytes); order-service requires the same INTERNAL key.
3. Configure Spring datasource credentials (SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD) and JDBC URL using the local profile.
4. Optionally configure SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, SMTP_AUTH, SMTP_STARTTLS, USER_EMAIL_FROM and USER_PUBLIC_BASE_URL.
5. Run: mvn -pl user-service -am verify ; mvn -pl user-service -am spring-boot:run

Endpoints:

Public POST /api/v1/auth/register, /login, /refresh, /verify-email, /password-reset/request, /password-reset/confirm.
Authenticated POST /api/v1/auth/logout, /logout-all, /change-password, /verify-email/request.
Authenticated GET,PATCH /api/v1/users/me and POST /api/v1/users/me/deactivate.
Authenticated GET,POST /api/v1/users/me/addresses; PUT,DELETE /api/v1/users/me/addresses/{id}; PUT /api/v1/users/me/addresses/{id}/default.
Authenticated GET,PUT /api/v1/users/me/preferences.
Service-only GET /api/v1/internal/users/{userId} and legacy /api/v1/users/get-by-id, /get-by-email.

Request examples:
Registration: {"email":"a@example.com","phoneNumber":"+919876543210","fullName":"Test User","password":"A-Secure-Example-Passphrase"}
Login: {"email":"a@example.com","password":"A-Secure-Example-Passphrase","deviceId":"android"}
Refresh: {"refreshToken":"the-single-use-token"}
Profile patch: {"fullName":"Updated Name"}
Logout: {"refreshToken":"the-current-refresh-token"}

Secrets are required for startup. Keep real secrets out of git; use a secrets manager, TLS and least-privilege DB accounts. Docker Compose's hardcoded MySQL root user/password is demo-only and MUST be replaced before deployment. Existing access tokens live until expiry on single-session logout; logout-all/password change/deactivation invalidate immediately (DB tokenVersion check).

Security and scaling gaps before production claim: untrusted-IP/device abuse and password-reset-email flooding defense, audit retention/PII controls, email deliverability, SMS verification, key rotation/KMS/JWKS, coherent revocation-aware cache (every JWT request currently reads MySQL), MySQL/Testcontainers integration tests, cross-service API contract tests, high-load capacity, load shedding, Redis-rate-limit path, SLO monitoring, WAF, TLS/mTLS, high availability, PITR backups. Swiggy/Zomato-scale throughput is NOT established by this implementation alone.
