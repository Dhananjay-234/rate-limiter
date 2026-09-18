# Distributed API Rate Limiter

Per-tenant API rate limiting on Spring Boot and Redis, with two pluggable algorithms,
atomic enforcement via Lua, circuit breaking, and a provisioned observability stack.

`Spring Boot 3.3` · `Spring Cloud Gateway` · `Redis 7` · `Postgres 16` · `Resilience4j` · `Prometheus` · `Grafana` · `Java 17` (Maven; Docker images use Temurin 21)

---

## The problem

Two gateway instances handle a request for the same tenant simultaneously. Both read the
remaining quota, both see `1`, both admit. Two requests were allowed against a quota of
one. This is a lost update, and it happens constantly under load — not rarely.

The fix is to make the read-modify-write indivisible by moving it into a Redis Lua script,
which Redis executes atomically.

**I measured the difference instead of asserting it:**

| Implementation | Admitted | Quota | Over-admission |
|---|---|---|---|
| Naive `GET` → compute → `SET` | **640** | 100 | **+540%** |
| Atomic Lua script | **100** | 100 | **0** |

64 threads, 640 requests, bucket capacity 100, same Redis. Three consecutive runs each:
the naive version admitted 640/640/640, the atomic version 100/100/100.

---

## Measured results

**Latency** — 5,000 calls per algorithm, loopback TCP:

| Algorithm | mean | p50 | p95 | p99 |
|---|---|---|---|---|
| Token bucket | 0.015 ms | 0.014 ms | 0.024 ms | 0.037 ms |
| Sliding window | 0.015 ms | 0.014 ms | 0.025 ms | 0.038 ms |

Effectively identical. Latency is not the reason to choose between them.

**Memory** — after 1,000 requests against one key:

| Algorithm | Per key | At 10k tenants |
|---|---|---|
| Token bucket | 96 bytes (constant) | ~0.9 MB |
| Sliding window | ~97,000 bytes (linear) | ~950 MB |

Around **1,000×** difference. This is the real trade-off, and it's a capacity decision rather
than a performance one.

---

## Architecture

```
client ──▶ Spring Cloud Gateway ──┬──▶ JWT validation (tenant identity)
  (JWT)                           ├──▶ tenant config  (Redis cache ◀─ Postgres)
                                  ├──▶ rate limit     (atomic Lua on Redis)
                                  └──▶ circuit breaker ──▶ service-a / service-b
                                              │
                                     Micrometer ──▶ Prometheus ──▶ Grafana
```

| Module | Purpose |
|---|---|
| `rate-limiter-core` | Algorithms, Lua scripts, metrics — a library, no web deps |
| `api-gateway` | Gateway, filter, JWT, tenant cache, admin endpoints |
| `service-a` / `service-b` | Downstreams with `/ping`, `/data`, and `/chaos` toggles for breaker demos |
| `infra` | Shared `Dockerfile` (`MODULE` arg), Prometheus scrape config, provisioned Grafana dashboard |

### How a request flows

1. Client calls the **gateway** with `Authorization: Bearer <JWT>` (mint one via `GET /dev/token`).
2. `JwtTenantResolver` validates the HMAC and reads `tenantId` + `tier` from claims.
3. `CachingTenantConfigService` loads algorithm and limits from **Postgres** (cached in **Redis**).
4. `RateLimiterGatewayFilterFactory` runs the tenant’s Lua script on Redis; on success it forwards the request.
5. Spring Cloud Gateway routes `/api/a/**` and `/api/b/**` to the backends (`StripPrefix=2`, so `/api/a/ping` → downstream `/ping`).
6. On downstream failure, **Resilience4j** opens the circuit and `/fallback/{service}` returns a structured 503.

Tier defaults (requests per minute): **FREE 60**, **PRO 300**, **ENTERPRISE 1000**. Seeded rows in `schema.sql` can override algorithm or set `custom_limit` (e.g. `acme-custom` → 120/min).

---

## Quick start

```bash
docker compose up --build
```

This starts **Redis**, **Postgres**, **service-a**, **service-b**, **api-gateway**, **Prometheus**, and **Grafana**. All three Spring apps read `server.port` from the environment with local defaults below.

| Service | Default URL (local compose) | Listen port config |
|---|---|---|
| API gateway | http://localhost:8080 | `PORT` → `${PORT:8080}` |
| service-a | http://localhost:8081 | `PORT` → `${PORT:8081}` |
| service-b | http://localhost:8082 | `PORT` → `${PORT:8082}` |
| Grafana | http://localhost:3000 | (container image) |
| Prometheus | http://localhost:9090 | (container image) |

The gateway discovers backends with **host:port** (no full URI in config):

| Variable | Local default | Docker Compose value |
|---|---|---|
| `SERVICE_A_HOSTPORT` | `localhost:8081` | `service-a:8081` |
| `SERVICE_B_HOSTPORT` | `localhost:8082` | `service-b:8082` |

Routes in `api-gateway` are `uri: http://${SERVICE_A_HOSTPORT:localhost:8081}` (and the same pattern for B). Other common gateway env vars: `REDIS_HOST`, `REDIS_PORT`, `POSTGRES_*`, `JWT_SECRET`, `RATELIMITER_ENABLED`, `RATELIMITER_FAIL_OPEN`.

```bash
# Mint a demo token
TOKEN=$(curl -s "http://localhost:8080/dev/token?tenantId=acme-free&tier=FREE" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Inspect quota headers
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping

# Same route on service B
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/b/ping

# Exhaust the 60/min FREE quota
for i in $(seq 1 70); do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
done; echo
```

**Browser demo:** open [`index.html`](index.html) in a browser (gateway must be at `http://localhost:8080`). If the browser blocks cross-origin calls, add a CORS filter on the gateway for your page origin.

---

## Deploy on Render

[`render.yaml`](render.yaml) defines Postgres, Redis, three Docker web services (`service-a`, `service-b`, `api-gateway`), and wires env vars automatically:

- Each web service sets `MODULE` and `PORT` (8081 / 8082 / 8080).
- Render injects its own `PORT` at runtime; Spring binds via `${PORT:…}` so the process listens on the platform port.
- The gateway gets `SERVICE_A_HOSTPORT` and `SERVICE_B_HOSTPORT` from Render’s `hostport` property (internal `host:port`, no `http://` prefix — the YAML adds the scheme).

Apply the blueprint from the Render dashboard or connect the repo with Blueprint sync.

---

## Features

- **Two algorithms**, switchable per tenant without a restart (strategy pattern)
- **Atomic enforcement** — the whole check-and-decrement is one Lua script
- **Per-tenant, per-route quotas** — FREE / PRO / ENTERPRISE, plus per-tenant overrides
- **JWT-derived identity** — never a client-controlled header
- **Fail-open with visibility** — Redis outage admits traffic, increments a counter, sets `X-RateLimit-Degraded`
- **Circuit breaking** — Resilience4j count-based window (10 calls, 50% failure threshold, min 5 calls), structured 503 fallback
- **Provisioned Grafana** — 7 panels, appears automatically, no manual import
- **Testcontainers concurrency tests** — real Redis, `@RepeatedTest(5)`

### Response headers

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 43
X-RateLimit-Reset: 1727123456
Retry-After: 12              (on 429 only)
X-RateLimit-Degraded: true   (when Redis was unreachable)
```

---

## Chaos testing

Chaos endpoints are on the **downstream** ports (8081 / 8082), not through the gateway path prefix.

```bash
# Circuit breaker (toggle failures on service-a, then hit the gateway route)
curl http://localhost:8081/chaos/on
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping   # eventually 503 via fallback
curl http://localhost:8081/chaos/off

# Redis outage — fail-open path
docker stop rl-redis
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
# 200 with X-RateLimit-Degraded: true
docker start rl-redis
```

---

## Testing

```bash
mvn test
```

| Test | Proves |
|---|---|
| `TokenBucketConcurrencyTest` | 500 parallel callers never exceed capacity |
| `SlidingWindowLimiterTest` | 400 parallel callers admit exactly the limit |
| Refill test | Tokens return at the expected rate |
| Retry-After test | Rejections carry a usable backoff hint |

Both use Testcontainers against real Redis. A mock cannot exhibit the race these tests
exist to rule out.

---

## Configuration

Application properties (see each module’s `application.yml`):

```yaml
server:
  port: ${PORT:8080}   # 8081 / 8082 on service-a and service-b

ratelimiter:
  enabled: ${RATELIMITER_ENABLED:true}      # false = limiter skipped (baseline benchmarks)
  fail-open: ${RATELIMITER_FAIL_OPEN:true}  # false = 503 when Redis is down
  key-ttl-seconds: 3600
  tenant-cache-ttl-seconds: 60
```

Seeded demo tenants (Postgres `tenants` table via `schema.sql`):

| Tenant | Tier | Algorithm | Effective limit |
|---|---|---|---|
| `acme-free` | FREE | Token bucket | 60/min |
| `acme-pro` | PRO | Token bucket | 300/min |
| `acme-enterprise` | ENTERPRISE | Token bucket | 1000/min |
| `acme-sliding` | PRO | Sliding window | 300/min |
| `acme-custom` | FREE | Token bucket | 120/min (`custom_limit`) |

Token minting (`GET /dev/token?tenantId=…&tier=…`) embeds the tier in the JWT; limits still come from the tenant row when present.

---

## Design decisions

Covered in full in [DOC.md](DOC.md), including the reasoning behind each:

- Why Lua rather than `INCR` — and why a fixed-window counter has a boundary problem
- Why the clock is passed into the script rather than read inside it
- Why the token bucket is the default despite being less exact
- Why fail-open is the default, and when fail-closed is the right answer
- Why sorted-set members are `timestamp-uuid` rather than just the timestamp

## Limitations and scaling

Also in [DOC.md](DOC.md): single Redis instance, clock skew assumptions, unbounded
sliding-window memory within a window, and what changes at scale — Cluster with hash-tagged
keys, two-layer local/global limiting, Sentinel, `LISTEN/NOTIFY` for config propagation.

---

## License

MIT
