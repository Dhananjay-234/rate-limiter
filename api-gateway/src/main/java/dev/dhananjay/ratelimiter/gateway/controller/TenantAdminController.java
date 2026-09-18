package dev.dhananjay.ratelimiter.gateway.controller;

import dev.dhananjay.ratelimiter.core.tenant.TenantConfigService;
import dev.dhananjay.ratelimiter.gateway.tenant.TenantEntity;
import dev.dhananjay.ratelimiter.gateway.tenant.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Internal CRUD for tenant quotas. In a real deployment this sits behind network
 * policy or an admin scope — it is exposed plainly here to keep the demo runnable.
 */
@RestController
@RequestMapping("/internal/tenants")
public class TenantAdminController {

    private final TenantRepository repository;
    private final TenantConfigService configService;

    public TenantAdminController(TenantRepository repository, TenantConfigService configService) {
        this.repository = repository;
        this.configService = configService;
    }

    @GetMapping
    public Flux<TenantEntity> list() {
        return repository.findAll();
    }

    @GetMapping("/{tenantId}")
    public Mono<ResponseEntity<TenantEntity>> get(@PathVariable String tenantId) {
        return repository.findById(tenantId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Upsert. The cache is evicted straight after the write so the new tier takes
     * effect on the next request instead of waiting out the TTL.
     */
    @PutMapping("/{tenantId}")
    public Mono<TenantEntity> upsert(@PathVariable String tenantId,
                                     @RequestBody TenantEntity body) {
        body.setTenantId(tenantId);
        return repository.save(body)
                .flatMap(saved -> configService.evict(tenantId).thenReturn(saved));
    }

    @DeleteMapping("/{tenantId}")
    public Mono<Void> delete(@PathVariable String tenantId) {
        return repository.deleteById(tenantId)
                .then(configService.evict(tenantId));
    }
}
