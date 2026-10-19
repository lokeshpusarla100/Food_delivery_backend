# Restaurant Service — Catalog, Merchants, Availability

**Status:** implemented on a draft PR. CI is required; do not treat this as deployment-ready without security, load, and operational verification.

## Core architecture

- Java 17, Spring Boot 3.2, Spring Security 6 Resource Server, MySQL 8, Flyway and JDBC for explicit SQL/queries.
- Additive schema evolution from the existing normalized V1 catalog; original Flyway migration remains untouched.
- Restaurant brands with owner/manager/editor staff table (per-brand scope); platform bootstrap is machine-key restricted.
- Outlets with location POINT SRID 4326, validated WGS84 longitude/latitude, timezone, bounded geospatial search and pagination, opening hours incl. overnight, blackouts, and merchant pause control.
- Categories, menu items with vegetarian flag/allergens, modifier group min/max rules, outlet availability/override pricing, immutable price history and optimistic item price version.
- Read endpoints are public; merchant writes require a validated user JWT plus membership in the restaurant_staff table. Internal quote endpoint requires X-Restaurant-Internal-Key.
- Transactional outbox rows are written alongside domain mutations. **A publisher is not included yet; without one, events remain pending.**
- Catalog quotes use authoritative menu prices and validated modifier selections, not hardcoded pricing. Quotes are non-reserving and must be revalidated at checkout.

## Required runtime configuration

- USER_JWT_SECRET: same 32+ byte secret used by the User Service HS256 issuer, with matching issuer/audience. **Use secret manager / TLS. HS256 sharing is a transitional compatibility mechanism, NOT the preferred multi-service model; move to asymmetric issuer/JWKS.**
- RESTAURANT_PLATFORM_ADMIN_KEY: 32+ unpredictable bytes. For *initial brand bootstrap only*. Protect this endpoint with network controls as well as the key.
- RESTAURANT_INTERNAL_API_KEY: 32+ unpredictable bytes, held only by trusted backend services; use service mesh mTLS/network policy.
- SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD.
- Eureka URL for local or Docker mode. Do not reuse demo-only root DB passwords in production.

## Main API surface

Public GET: /api/v1/brands, /api/v1/brands/{brandId}, /api/v1/outlets/{outletId},
/api/v1/outlets/{outletId}/validate, /api/v1/outlets/near?lat=...&lon=...&radiusKm=5,
/api/v1/outlets/search?city=..., /api/v1/outlets/brand/{brandId}, /api/v1/menus/{outletId},
/api/v1/menus/brands/{brandId}/categories.

Platform only: POST /api/v1/platform/brands {name,ownerUserId,...}; DELETE /api/v1/platform/brands/{brandId}.

Merchant JWT only: POST /api/v1/manage/brands/{brandId}/outlets, /api/v1/manage/brands/{brandId}/categories,
/api/v1/manage/items, /api/v1/manage/brands/{brandId}/modifier-groups,
/api/v1/manage/modifier-groups/{groupId}/modifiers,
/api/v1/manage/outlets/{outletId}/blackouts; PUT /api/v1/manage/outlets/{outletId}/hours,
/api/v1/manage/outlets/{outletId}/accepting-orders,
/api/v1/manage/outlets/{outletId}/items/{itemId},
/api/v1/manage/items/{itemId}/price,
/api/v1/manage/items/{itemId}/modifier-groups/{groupId}; DELETE item/category/outlet; POST/DELETE staff.

Backend-only: POST /api/v1/internal/outlets/{outletId}/quote
{items:[{itemId,quantity,modifierIds:[]}]}. Returns INR item totals excluding tax, delivery, checkout fees.

## Release blockers / scaling

- High-volume queries currently use SQL with multiple per-row schedule/menus reads; add bulk projections and coherent multi-instance Redis cache with invalidation and load-test it. Bounded pages and geospatial bounding boxes are safety rails, not proof of throughput.
- Brand onboarding needs verified owner identity and a proper privileged workflow. JWT signature can remain valid after user deactivation until 15m expiry because Restaurant Service does not re-query User Service token versions. Use unified identity provider and revocation strategy.
- Implement and test outbox publisher/consumer semantics, bulk ingestion, upload scanning, merchant audit trails, menu service versioning, mobile compatibility and resilient search indexing.
- Private-network restrictions and WAF/edge rate limiting, secret rotation, security audit, backup/restore, canary deploy, metrics/tracing, capacity and failover tests remain required.
- **Do not claim Swiggy/Zomato load** without a workload model, SLOs and independently measured p95/p99 latency and failure rates.
