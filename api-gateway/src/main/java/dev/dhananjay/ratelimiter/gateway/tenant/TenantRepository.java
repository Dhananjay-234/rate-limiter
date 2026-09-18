package dev.dhananjay.ratelimiter.gateway.tenant;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends ReactiveCrudRepository<TenantEntity, String> {
}
