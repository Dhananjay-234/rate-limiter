CREATE TABLE IF NOT EXISTS tenants (
    tenant_id     VARCHAR(128) PRIMARY KEY,
    tier          VARCHAR(32)  NOT NULL DEFAULT 'FREE',
    algorithm     VARCHAR(32)  NOT NULL DEFAULT 'TOKEN_BUCKET',
    custom_limit  BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Demo tenants: one per tier, plus one forced onto the sliding window so both
-- algorithms can be compared side by side without a restart.
INSERT INTO tenants (tenant_id, tier, algorithm, custom_limit) VALUES
    ('acme-free',       'FREE',       'TOKEN_BUCKET',   0),
    ('acme-pro',        'PRO',        'TOKEN_BUCKET',   0),
    ('acme-enterprise', 'ENTERPRISE', 'TOKEN_BUCKET',   0),
    ('acme-sliding',    'PRO',        'SLIDING_WINDOW', 0),
    ('acme-custom',     'FREE',       'TOKEN_BUCKET', 120)
ON CONFLICT (tenant_id) DO NOTHING;
