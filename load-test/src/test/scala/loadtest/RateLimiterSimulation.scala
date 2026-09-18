package loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/**
 * Ramp test against the gateway.
 *
 * Run it twice to produce the overhead number for the README:
 *
 *   1. baseline  — RATELIMITER_ENABLED=false on the gateway
 *   2. protected — RATELIMITER_ENABLED=true
 *
 * The difference in p99 between the two runs is the cost the limiter adds. Reporting
 * absolute p99 without a baseline is meaningless, because it mostly measures the
 * downstream and the network rather than the limiter.
 */
class RateLimiterSimulation extends Simulation {

  private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  // ENTERPRISE tier (1000/min) so the ramp measures limiter cost rather than
  // spending most of the run in the rejection path.
  private val tenantId = System.getProperty("tenantId", "acme-enterprise")
  private val tier     = System.getProperty("tier", "ENTERPRISE")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .shareConnections

  /** Fetch a token once at startup and reuse it for every virtual user. */
  private val fetchToken = exec(
    http("mint token")
      .get(s"/dev/token?tenantId=$tenantId&tier=$tier")
      .check(jsonPath("$.token").saveAs("token"))
  )

  private val callGateway = exec(
    http("GET /api/a/ping")
      .get("/api/a/ping")
      .header("Authorization", "Bearer ${token}")
      // 429 is a correct outcome under load, not a failure of the system.
      .check(status.in(200, 429))
      .check(status.saveAs("httpStatus"))
  )

  private val scn = scenario("Rate limiter ramp")
    .exec(fetchToken)
    .during(5.minutes) {
      exec(callGateway).pause(10.milliseconds)
    }

  setUp(
    scn.inject(
      rampUsersPerSec(10).to(1000).during(2.minutes),
      constantUsersPerSec(1000).during(3.minutes)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(50),   // p99 under 50ms end to end
      global.failedRequests.percent.lt(1.0)     // non-2xx/429 stays under 1%
    )
}
