# Tessellation Justfile

# Shows help
default:
    @just --list --justfile {{ justfile() }}


# Make sure dependencies are installed before running any recipe
_check_deps:
	@bash docker/bin/install_dependencies.sh

# Main test command: recompile, setup docker environment, run all e2e tests (including metagraph). Use --test=<name> to run specific tests, --list-tests to see available tests, --skip-assembly to reuse JARs.
test *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh --use-test-metagraph --num-gl0=3 {{ extra_args }}

# Bring up the default test environment, starting docker images but without running any tests or checks
# Use --hypergraph-release=<tag> to use pre-built JARs from a release (e.g., --hypergraph-release=v3.5.11)
# This is useful for metagraph development against a stable tessellation version
up *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh --up {{ extra_args }}

# Destroy test environment, alias for clean-docker
down *extra_args:
	@just clean-docker

# Build the docker images and test environment, without running any containers
build *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh --build {{ extra_args }}

# Deploy a full cluster for an environment with one command (testnet => release/testnet
# branches for tessellation + snapshot-streaming, increase_delegated_stakes for block_explorer).
# Builds node software from the env's git ref, then deploys nodes + streaming + monitoring.
deploy env="testnet" *args:
	@bash deploy/deploy-cluster.sh {{ env }} {{ args }}

purge-docker:
	@bash docker/bin/purge-docker.sh

clean-docker:
	@bash docker/bin/tessellation-docker-cleanup.sh

# Remove root-owned node data and logs for all layer types using a Docker container to bypass sudo
# Covers: gl0, gl1, ml0, cl1, dl1 — both data and logs directories
# Data lives in nodes/ (repo root, used by compose-runner) AND docker/nodes/ (legacy)
clean-data:
	@docker run --rm -v $(pwd)/nodes:/nodes alpine sh -c "\
	  for layer in gl0 gl1 ml0 cl1 dl1; do \
	    rm -rf /nodes/*/\$layer-data /nodes/*/\$layer-logs; \
	  done" 2>/dev/null || true
	@docker run --rm -v $(pwd)/docker/nodes:/nodes alpine sh -c "\
	  for layer in gl0 gl1 ml0 cl1 dl1; do \
	    rm -rf /nodes/*/\$layer-data /nodes/*/\$layer-logs; \
	  done" 2>/dev/null || true
	@echo "Node data and logs cleaned for gl0/gl1/ml0/cl1/dl1 (nodes/ and docker/nodes/)"

clean-configs:
	@bash docker/bin/clean-configs.sh

clean:
	@bash sbt clean
	@bash -c "cd .github/templates/metagraphs/project_template && sbt clean"
	@just clean-configs
	@just clean-docker

debug-main:
	@just _check_deps
	@bash docker/bin/debug/mn-replicate.sh

# Start local monitoring stack (Prometheus + Grafana + ClickHouse).
# For ClickHouse logging, start monitoring BEFORE the cluster so nodes can connect at boot:
#   just monitoring-up && CLICKHOUSE_HOST=172.32.0.200 CLICKHOUSE_PASSWORD=clickhouse CLICKHOUSE_PORT=8123 CLICKHOUSE_PROTOCOL=http just up
monitoring-up:
	@docker network create --driver=bridge --subnet=172.32.0.0/24 tessellation_common 2>/dev/null || true
	@docker compose -f docker/monitoring/docker-compose.local.yaml up -d
	@echo "Prometheus: http://localhost:9090"
	@echo "Grafana:    http://localhost:3000  (admin/admin)"

# Stop local monitoring stack. Preserves data volumes.
monitoring-down:
	@docker compose -f docker/monitoring/docker-compose.local.yaml down

check:
    @bash sbt --error 'scalafixAll --check --rules OrganizeImports;scalafmtCheckAll;test'

# Generate test user keys for bulk transaction testing
generate-test-keys num_keys='10':
	@bash docker/bin/generate-test-user-keys.sh {{ num_keys }}
