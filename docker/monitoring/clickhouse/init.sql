-- Pre-create tables without TTL — logs are kept indefinitely.
-- Nodes use CREATE TABLE IF NOT EXISTS, so they will reuse these tables.

CREATE TABLE IF NOT EXISTS nightly_logs (
    timestamp DateTime64(3),
    node_id LowCardinality(String),
    network_id LowCardinality(String),
    log_type LowCardinality(String),
    data JSON
) ENGINE = MergeTree()
PARTITION BY (network_id, toYYYYMM(timestamp))
ORDER BY (node_id, timestamp);

CREATE TABLE IF NOT EXISTS nightly_logs_consensus (
    timestamp DateTime64(3),
    node_id LowCardinality(String),
    network_id LowCardinality(String),
    ordinal UInt64,
    event_type LowCardinality(String),
    facilitators Array(String),
    INDEX idx_ordinal ordinal TYPE minmax GRANULARITY 1,
    INDEX idx_event_type event_type TYPE set(20) GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY (network_id, toYYYYMM(timestamp))
ORDER BY (node_id, ordinal, timestamp);

-- NOTE: TTL removal is done in deploy-monitoring.sh as a best-effort step, NOT here.
-- `ALTER TABLE ... REMOVE TTL` errors (code 36) on a table that has no TTL, which would
-- abort this entire init script (clickhouse-client multiquery stops on first error) and
-- leave the deploy half-applied. Tables above are created without TTL, so there's nothing
-- to remove on a fresh cluster anyway.
