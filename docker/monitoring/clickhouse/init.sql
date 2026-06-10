-- Pre-create tables with 3-day retention for the nightly cluster.
-- Nodes use CREATE TABLE IF NOT EXISTS, so they will reuse these tables.

CREATE TABLE IF NOT EXISTS nightly_logs (
    timestamp DateTime64(3),
    node_id LowCardinality(String),
    network_id LowCardinality(String),
    log_type LowCardinality(String),
    data JSON
) ENGINE = MergeTree()
PARTITION BY (network_id, toYYYYMM(timestamp))
ORDER BY (node_id, timestamp)
TTL toDateTime(timestamp) + INTERVAL 3 DAY;

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
ORDER BY (node_id, ordinal, timestamp)
TTL toDateTime(timestamp) + INTERVAL 3 DAY;