# Distributed API Rate Limiter — Project Documentation

A per-tenant API rate limiter built on Spring Boot, Redis, Postgres, and Spring Cloud Gateway.
Two algorithms, atomic enforcement, circuit breaking, and a full observability stack.

Stack: Spring Boot 3.3 · Java 17 (Maven compile target; Docker images use Temurin 21) · Redis 7 · Postgres 16.

---

## 1. What this is, and why it's harder than it looks

A rate limiter sounds like a counter with an `if` statement. The moment you run more
than one instance of your gateway, it stops being one.

Suppose two gateway pods both handle a request for the same tenant at the same instant.
Each reads the tenant's remaining quota from Redis, sees `1`, decides the request is
allowed, and writes back `0`. Two requests were admitted against a quota of one. This is
a **lost update**, and it is not a rare edge case — it happens constantly under load.

The fix is to make the entire read-modify-write sequence indivisible. Redis executes Lua
scripts atomically: while a script runs, no other command from any other client executes.
Moving the whole decision into a Lua script turns a race into a guarantee.

**This project measures the difference rather than asserting it.** Section 3 has the numbers.

---

## 2. Architecture

```
                        ┌──────────────────────────────┐
   client ─────────────▶│      Spring Cloud Gateway     │
   (Bearer JWT)         │                               │
                        │  1. JwtTenantResolver         │
                        │     validate signature,       │
                        │     extract tenantId + tier   │
                        │              │                │
                        │              ▼                │
                        │  2. TenantConfigService       │──────▶ Redis (config cache, 60s TTL)
                        │     resolve limit + algorithm │──────▶ Postgres (source of truth)
                        │              │                │
                        │              ▼                │
                        │  3. RateLimiterGatewayFilter  │
                        │     EVALSHA <lua> ───────────────────▶ Redis (atomic check)
                        │              │                │
                        │       ┌──────┴──────┐         │
                        │   429 │             │ allow   │
                        │       ▼             ▼         │
                        │  reject      4. CircuitBreaker│
                        └──────────────────────┬────────┘
                                               │
                                ┌──────────────┴──────────────┐
                                ▼                             ▼
                          service-a (PORT default 8081)   service-b (PORT default 8082)
```

Metrics flow out of the gateway via Micrometer → Prometheus → Grafana.

### Modules

| Module | Responsibility |
|---|---|
| `rate-limiter-core` | Algorithms, Lua scripts, metrics, configuration properties. No web dependencies — it is a library. |
| `api-gateway` | Spring Cloud Gateway, the filter, JWT validation, tenant cache, `/dev/token`, `/internal/tenants`, fallbacks. |
| `service-a`, `service-b` | Trivial downstreams: `/ping`, `/data`, and `/chaos/on` · `/chaos/off` for breaker demos. |
| `infra` | Shared `Dockerfile` (build arg `MODULE`), Prometheus scrape config, provisioned Grafana dashboard. |

### End-to-end request path

1. Client hits the **gateway** (default `http://localhost:8080`) with `Authorization: Bearer <JWT>`.
2. `JwtTenantResolver` — verifies the HMAC signature, pulls `tenantId` and `tier` claims (`401` if invalid).
3. `CachingTenantConfigService` — Redis cache in front of Postgres (algorithm, tier, `custom_limit`).
4. `RateLimiterGatewayFilterFactory` — `RateLimiterFactory` dispatches to `TokenBucketLimiter` or `SlidingWindowLimiter`; each runs one Lua script via `EVALSHA` (`429` when exhausted).
5. Spring Cloud Gateway forwards allowed requests: routes `/api/a/**` and `/api/b/**` use `StripPrefix=2`, so e.g. `/api/a/ping` becomes downstream `/ping`. Backend URIs are `http://${SERVICE_A_HOSTPORT:localhost:8081}` and the same pattern for B (host:port env vars, not a full URI).
6. `CircuitBreaker` filter — on sustained downstream failure, Resilience4j opens and `/fallback/{service}` returns a structured `503`.

### Request path in code (class map)

1. `RateLimiterGatewayFilterFactory` — filter applied per route in `application.yml`
2. `JwtTenantResolver` — JWT verification and tenant claims
3. `CachingTenantConfigService` — cached tenant rows
4. `RateLimiterFactory` — algorithm dispatch
5. `TokenBucketLimiter` / `SlidingWindowLimiter` — Lua-backed limiters

Seeded demo tenants (`api-gateway/src/main/resources/schema.sql`):

| Tenant | Tier | Algorithm | Effective limit |
|---|---|---|---|
| `acme-free` | FREE | Token bucket | 60/min |
| `acme-pro` | PRO | Token bucket | 300/min |
| `acme-enterprise` | ENTERPRISE | Token bucket | 1,000/min |
| `acme-sliding` | PRO | Sliding window | 300/min |
| `acme-custom` | FREE | Token bucket | 120/min (`custom_limit`) |

`GET /dev/token?tenantId=…&tier=…` embeds tier in the JWT; when the tenant exists in Postgres, limits and algorithm come from that row.

---

## 3. Measured results

All numbers below were produced against a real Redis 7 instance, not estimated.

### 3.1 The atomicity proof

The same workload — 64 OS threads, 640 total requests, bucket capacity 100 — run against
two implementations that differ only in where the read-modify-write happens.

| Implementation | Admitted | Expected | Over-admission |
|---|---|---|---|
| Naive `GET` → compute → `SET` from the client | **640** | 100 | **+540%** |
| Atomic Lua script (this project) | **100** | 100 | **0** |

Three consecutive runs of each. The naive version admitted 640/640/640. The Lua version
admitted 100/100/100 — not "about 100", exactly 100, every time.

The naive implementation did not fail subtly. It admitted *every single request*, because
64 threads reading concurrently almost never observe each other's decrements. The final
stored value was 80, which shows the writes were happening and being lost.

> **This is the single most valuable result in the project.** It converts "I used Lua for
> atomicity" from a claim into a measurement.

### 3.2 Algorithm latency

5,000 sequential calls per algorithm, loopback TCP, measured client-side.

| Algorithm | mean | p50 | p95 | p99 |
|---|---|---|---|---|
| Token bucket | 0.015 ms | 0.014 ms | 0.024 ms | 0.037 ms |
| Sliding window | 0.015 ms | 0.014 ms | 0.025 ms | 0.038 ms |

Latency is effectively identical at low occupancy — both are a single round trip and the
script bodies are short. **Latency is not the reason to pick one over the other.**

### 3.3 Memory — where they actually differ

After 1,000 requests against a single key:

| Algorithm | Bytes per key | Growth |
|---|---|---|
| Token bucket | **96 bytes** | Constant — two hash fields regardless of traffic |
| Sliding window | **~97,000 bytes** | Linear — roughly 97–100 bytes per request in the window |

Around **1,000× more memory** for the sliding window (the exact figure moves a few percent
between runs depending on Redis's ziplist-to-skiplist encoding threshold). Projected to 10,000 tenants each running
1,000 requests per minute:

| Algorithm | Redis memory |
|---|---|
| Token bucket | ~0.9 MB |
| Sliding window | ~950 MB |

This is the real trade-off, and it is a capacity-planning decision rather than a
performance one.

### 3.4 Choosing between them

| | Token bucket | Sliding window |
|---|---|---|
| Burst tolerance | Allows bursts up to capacity | None — strictly smoothed |
| Memory | O(1) per tenant | O(requests in window) |
| Accuracy at window edges | Approximate | Exact |
| Best for | General API traffic, bursty clients | Strict quotas, expensive downstreams |

**Default is the token bucket.** Most API clients are naturally bursty — a page load
firing six requests at once is normal behaviour, not abuse. Rejecting the sixth because
it arrived in the same 10 ms window produces a worse product for no real protection.
The sliding window is available per tenant for cases where the downstream genuinely
cannot absorb bursts.

---

## 4. The Lua scripts

### 4.1 Token bucket

The bucket holds `capacity` tokens and refills at `capacity / 60` tokens per second.
There is no background timer — refill is computed lazily from the elapsed time since the
last request:

```lua
local elapsed_seconds = math.max(0, (now_millis - last_refill) / 1000.0)
tokens = math.min(capacity, tokens + (elapsed_seconds * refill_rate))

if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after = math.ceil((1 - tokens) / refill_rate)
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now_millis)
redis.call('EXPIRE', key, ttl_seconds)
```

Two design details worth defending in an interview:

- **The clock is passed in as `ARGV[3]`, not read inside the script.** Redis replicates
  scripts by effect, but a script that reads `TIME` is non-deterministic and historically
  was rejected outright in replicated mode. Taking the timestamp from the caller keeps the
  script deterministic and replication-safe.
- **`EXPIRE` on every call.** Idle tenants must not accumulate keys forever. The TTL is
  refreshed on each request, so an active bucket never expires mid-use and an abandoned
  one disappears an hour later.

### 4.2 Sliding window

A sorted set per key, scored by request timestamp:

```lua
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)   -- evict what slid out
local used = redis.call('ZCARD', key)                  -- count what remains

if used < limit then
  redis.call('ZADD', key, now_millis, member_id)
  return { 1, limit - used - 1, reset_epoch, 0 }
end
```

The member id is `timestamp-uuid`, not just the timestamp. Two requests landing in the
same millisecond would otherwise collide onto one sorted-set member, and the second would
be silently free. That is a one-line bug that only shows up under load.

### 4.3 Script loading

Scripts are loaded once at startup via `DefaultRedisScript` with a `ClassPathResource`.
Spring Data Redis caches the SHA and issues `EVALSHA` per request, so the script body
crosses the wire once rather than on every call.

---

## 5. Failure modes and the decisions behind them

| Scenario | Behaviour | Reasoning |
|---|---|---|
| Redis unreachable | **Fail open** — admit the request, increment `rate_limiter_fail_open_total`, set `X-RateLimit-Degraded: true` | A rate limiter is a protection mechanism, not a correctness mechanism. Turning a Redis blip into a customer-facing 503 causes more damage than briefly over-admitting. The metric means this is never silent. |
| Postgres unreachable | Apply `FREE` tier defaults | Wrong in the safe direction: a paying customer is briefly throttled harder, rather than everyone getting unlimited access. |
| Unknown tenant | `FREE` tier | Same principle. No hard failure on the request path. |
| Invalid or missing JWT | `401`, before the quota check | Identity must be established first. Otherwise a caller spends someone else's quota by claiming their tenant id. |
| Downstream failing | Resilience4j count-based breaker (window 10, min 5 calls, 50% failure threshold); `/fallback/{service}` returns structured 503 | Stops a broken downstream from consuming gateway connections on timeouts it will never satisfy. |
| Unexpected exception in the filter | Governed by `ratelimiter.fail-open` | A bug in the limiter must not take the gateway down. |

**Fail-open is configurable, not hardcoded** (`ratelimiter.fail-open`). That matters:
a metered billing API would reasonably choose fail-closed, because admitting unbilled
requests costs real money. The right answer depends on what the quota is protecting.

---

## 6. Multi-tenancy

Tenant identity comes from a signed JWT claim, never from a header the client controls.

| Tier | Limit |
|---|---|
| FREE | 60 req/min |
| PRO | 300 req/min |
| ENTERPRISE | 1,000 req/min |

`custom_limit` on the tenant row overrides the tier default when greater than zero.

Config lives in Postgres and is cached in Redis with a 60-second TTL. The short TTL is
deliberate: it bounds how long a tier change takes to propagate across every gateway
instance without needing distributed cache invalidation. The admin endpoint evicts the
cache explicitly on write, so intentional changes apply immediately and the TTL only
covers the case where someone edits the database directly.

Redis keys are namespaced per tenant *and* per route:

```
rate_limit:tb:{tenantId}:{route}      hash     token bucket state
rate_limit:sw:{tenantId}:{route}      zset     sliding window timestamps
tenant_config:{tenantId}              string   cached config, 60s TTL
```

Per-route keys mean an expensive reporting endpoint can carry a tighter quota than a
cheap health check without a second limiter.

---

## 7. Observability

| Metric | Type | Purpose |
|---|---|---|
| `rate_limiter_requests_total` | counter | Tagged by tenant, route, algorithm, result — the main traffic view |
| `rate_limiter_redis_latency` | timer with percentiles | p50/p95/p99 of the Lua call itself |
| `rate_limiter_fail_open_total` | counter | Requests admitted without a quota check. Should be zero. |
| `resilience4j_circuitbreaker_state` | gauge | Breaker state per route |

The Grafana dashboard is provisioned from `infra/grafana/provisioning/dashboards/rate-limiter.json`
and appears automatically — no manual import. Seven panels covering throughput, rejection
rate, Redis latency percentiles, breaker state, fail-open count, per-tenant traffic, and
HTTP status distribution.

`rate_limiter_fail_open_total` deserves an alert in any real deployment. A non-zero value
means quotas are not being enforced.

---

## 8. Running it

```bash
docker compose up --build
```

Starts **Redis**, **Postgres**, **service-a**, **service-b**, **api-gateway**, **Prometheus**, and **Grafana**. Each Spring service binds its listen port from the environment:

| Service | Default URL (local compose) | `application.yml` |
|---|---|---|
| API gateway | http://localhost:8080 | `server.port: ${PORT:8080}` |
| service-a | http://localhost:8081 | `server.port: ${PORT:8081}` |
| service-b | http://localhost:8082 | `server.port: ${PORT:8082}` |
| Grafana | http://localhost:3000 (anonymous admin) | container image |
| Prometheus | http://localhost:9090 | container image |

The gateway routes to backends using **host:port** variables (Compose and Render set these; local defaults keep `localhost:8081` / `8082`):

| Variable | Default when unset | `docker-compose.yml` (api-gateway) |
|---|---|---|
| `SERVICE_A_HOSTPORT` | `localhost:8081` | `service-a:8081` |
| `SERVICE_B_HOSTPORT` | `localhost:8082` | `service-b:8082` |

Gateway routes: `uri: http://${SERVICE_A_HOSTPORT:localhost:8081}` (and the same for B). Other gateway env vars used in compose / cloud: `REDIS_HOST`, `REDIS_PORT`, `POSTGRES_*`, `JWT_SECRET`, `RATELIMITER_ENABLED`, `RATELIMITER_FAIL_OPEN`.

```yaml
# api-gateway / service-a / service-b — ports and limiter toggles
server:
  port: ${PORT:8080}   # 8081 / 8082 on downstream modules

ratelimiter:
  enabled: ${RATELIMITER_ENABLED:true}
  fail-open: ${RATELIMITER_FAIL_OPEN:true}
```

### Exercise the limiter

```bash
# Mint a demo token (dev endpoint — a real system uses an external IdP)
TOKEN=$(curl -s "http://localhost:8080/dev/token?tenantId=acme-free&tier=FREE" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Single request — inspect the quota headers
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/b/ping

# Exhaust the 60/min FREE quota and watch it flip to 429
for i in $(seq 1 70); do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
done; echo
```

**Browser demo:** open [`index.html`](index.html) at the repo root with the gateway at `http://localhost:8080` (token panel, single ping, burst chart). If the browser blocks cross-origin requests, add a CORS filter on the gateway for your page origin.

### Compare algorithms live

```bash
# acme-sliding is seeded onto SLIDING_WINDOW, acme-pro onto TOKEN_BUCKET,
# both at PRO tier (300/min) — same quota, different shaping
curl -s "http://localhost:8080/dev/token?tenantId=acme-sliding&tier=PRO"
```

### Chaos: circuit breaker

Chaos toggles are on **downstream** ports (8081 / 8082), not under `/api/a/…` on the gateway.

```bash
curl http://localhost:8081/chaos/on     # service-a starts failing
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
# repeat — breaker opens, /fallback/service-a serves 503
curl http://localhost:8081/chaos/off    # recovers automatically
```

### Chaos: Redis outage

```bash
docker stop rl-redis
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
# 200 with X-RateLimit-Degraded: true — failed open, counter incremented
docker start rl-redis
```

### Deploy on Render

[`render.yaml`](render.yaml) is a Blueprint for Postgres (`rl-postgres`), Redis (`rl-redis`), and three Docker web services built from `infra/Dockerfile`:

| Render service | `MODULE` | Configured `PORT` | Notes |
|---|---|---|---|
| `service-a` | `service-a` | 8081 | Render also injects runtime `PORT`; Spring uses `${PORT:8081}`. |
| `service-b` | `service-b` | 8082 | Same pattern. |
| `api-gateway` | `api-gateway` | 8080 | Wired to Redis, Postgres, and `SERVICE_*_HOSTPORT` via `fromService` / `fromDatabase`. |

The gateway receives `SERVICE_A_HOSTPORT` and `SERVICE_B_HOSTPORT` from Render’s internal **`hostport`** property (values like `service-a:8081` without `http://`; `application.yml` adds the scheme). `JWT_SECRET` is auto-generated on Render. Sync the blueprint from the Render dashboard or connect the Git repo with Blueprint deploy.

---

## 9. Testing

| Test | What it proves |
|---|---|
| `TokenBucketConcurrencyTest` | 500 parallel callers against a 60-token bucket admit at most capacity. `@RepeatedTest(5)` — a race that appears one run in twenty is still a race. |
| `SlidingWindowLimiterTest` | 400 parallel callers against a limit of 50 admit exactly 50, no tolerance (no refill inside the window). |
| Refill test | Tokens return over time at the expected rate. |
| Retry-After test | Rejections carry a usable backoff hint. |

Both concurrency tests use Testcontainers, so they run against real Redis rather than a
mock. A mock cannot exhibit the race the tests exist to rule out.

```bash
mvn test
```

---

## 10. Known limitations

Listing these is not a weakness — being able to state where your design stops is the
difference between having built something and having followed a tutorial.

- **Single Redis instance.** No Cluster, no Sentinel. Redis is a single point of failure,
  mitigated only by fail-open.
- **Clock skew.** Timestamps come from each gateway instance. Significantly skewed clocks
  across instances would distort refill calculations. NTP is assumed.
- **Sliding window memory is unbounded within a window.** A tenant with a very high limit
  holds a proportionally large sorted set.
- **Config changes rely on TTL or explicit eviction.** Editing Postgres directly takes up
  to 60 seconds to propagate.
- **The `/dev/token` endpoint mints its own JWTs.** Convenient for demos, wrong for
  production — token issuance belongs to an identity provider.
- **No per-IP limiting.** Only per-tenant. A single tenant's compromised credentials can
  still consume that tenant's full quota from anywhere.

---

## 11. What I would change to scale this

**Redis Cluster with hash-tagged keys.** Keys become `rate_limit:tb:{tenantId}:route` with
the tenant id inside `{}` so all of one tenant's keys hash to the same slot. A Lua script
cannot span slots, so this is a correctness requirement, not an optimisation.

**Two-layer limiting.** At very high request rates a Redis round trip per request becomes
the bottleneck. The standard answer is a local in-memory limiter per gateway node holding
its share of the quota, syncing with Redis periodically. This trades exactness for
throughput — you accept a bounded over-admission in return for removing Redis from the hot
path. Whether that trade is acceptable depends on what the quota protects.

**Redis Sentinel or managed Redis.** Removes the single point of failure that fail-open
currently papers over.

**Dynamic config without restart.** Postgres `LISTEN/NOTIFY` or a Kafka topic to push
tenant changes to every gateway instance, eliminating the TTL window.

**Prometheus alerting rules.** At minimum: `rate_limiter_fail_open_total > 0`, sustained
rejection rate above a threshold per tenant, and breaker-open duration.

---
