package loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/**
 * Drives two tenants with identical quotas (PRO, 300/min) but different algorithms,
 * so the admission *shape* can be compared rather than just the totals.
 *
 * The token bucket tenant should show a burst of admissions at the start of each
 * refill period; the sliding window tenant should show a flat admission rate.
 */
class AlgorithmComparisonSimulation extends Simulation {

  private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .shareConnections

  private def tenantScenario(name: String, tenantId: String) =
    scenario(name)
      .exec(
        http("mint token")
          .get(s"/dev/token?tenantId=$tenantId&tier=PRO")
          .check(jsonPath("$.token").saveAs("token"))
      )
      .during(3.minutes) {
        exec(
          http(s"$name /api/a/ping")
            .get("/api/a/ping")
            .header("Authorization", "Bearer ${token}")
            .check(status.in(200, 429))
        ).pause(20.milliseconds)
      }

  setUp(
    tenantScenario("token-bucket", "acme-pro")
      .inject(constantUsersPerSec(50).during(3.minutes)),
    tenantScenario("sliding-window", "acme-sliding")
      .inject(constantUsersPerSec(50).during(3.minutes))
  ).protocols(httpProtocol)
}
