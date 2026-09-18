package dev.dhananjay.ratelimiter.gateway.tenant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("tenants")
public class TenantEntity {

    @Id
    @Column("tenant_id")
    private String tenantId;

    @Column("tier")
    private String tier;

    @Column("algorithm")
    private String algorithm;

    @Column("custom_limit")
    private Long customLimit;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Long getCustomLimit() {
        return customLimit;
    }

    public void setCustomLimit(Long customLimit) {
        this.customLimit = customLimit;
    }
}
