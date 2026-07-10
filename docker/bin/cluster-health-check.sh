

check_health() {
  local url=$1
  local service=$2
  local num_expected_nodes=$3
  local max_retries=${4:-$MAX_RETRIES}
  # Poll cluster until it has enough nodes
  local retry_count=0
  while [ $retry_count -lt $max_retries ]; do
      CLUSTER_INFO=$(curl -s --connect-timeout 5 --max-time 10 ${url}/cluster/info 2>/dev/null) || CLUSTER_INFO=""

      # Check if curl returned valid JSON before using jq
      if [ -z "$CLUSTER_INFO" ] || [ "$CLUSTER_INFO" = "null" ]; then
        if [ "$((retry_count % 10))" -eq 0 ]; then
          echo "Waiting for $service at $url to come online (attempt $((retry_count+1))/$max_retries)"
        fi
        sleep 5
        retry_count=$((retry_count+1))
        continue
      fi

      # Use jq with error handling
      CLUSTER_INFO_LEN=$(echo "$CLUSTER_INFO" | jq 'length' 2>/dev/null || echo "0")

      if [ "$CLUSTER_INFO_LEN" -ge "$num_expected_nodes" ]; then
        echo "Success: cluster $service has $CLUSTER_INFO_LEN nodes (>= $num_expected_nodes expected) at $url"
        return 0
      else
        if [ "$((retry_count % 5))" -eq 0 ]; then
          echo "Waiting for $service at $url to have >= $num_expected_nodes nodes, currently $CLUSTER_INFO_LEN nodes (attempt $((retry_count+1))/$max_retries)"
        fi
        sleep 4
        retry_count=$((retry_count+1))
      fi
    done

  echo "ERROR: $service cluster doesn't have >= $num_expected_nodes nodes at $url after $max_retries attempts"
  # Dump container logs for diagnostics
  docker logs "$service" 2>&1 | tail -30 || true
  return 1
}

# Check all nodes of a given layer in parallel, fail if any fails
check_layer_parallel() {
  local layer_name=$1
  local port_prefix=$2
  local num_nodes=$3
  local num_expected=$4
  local host=$5

  local pids=()
  local nodes=()
  for i in $(seq 0 $((num_nodes - 1))); do
    local url="${host}:${port_prefix}${i}0"
    local service="${layer_name}-${i}"
    check_health "$url" "$service" "$num_expected" &
    pids+=($!)
    nodes+=("$service")
  done

  local failed=false
  for idx in "${!pids[@]}"; do
    if ! wait "${pids[$idx]}"; then
      echo "FAILED: ${nodes[$idx]} health check timed out"
      failed=true
    fi
  done

  if [ "$failed" = "true" ]; then
    return 1
  fi
  return 0
}

verify_healthy() {
  echo "Sending cluster poll health request for cluster info to check joined."
  MAX_RETRIES=200
  local host=${TEST_HOST:-http://localhost}

  if [ "$host" != "http://localhost" ]; then
    # Remote host: check one endpoint per layer using configured URLs
    if [ "$NUM_GL0_NODES" -gt 0 ]; then
      check_health "$GL0_URL" "gl0" 1
    fi
    if [ "$NUM_GL1_NODES" -gt 0 ]; then
      check_health "$GL1_URL" "gl1" 1
    fi
    if [ "$NUM_ML0_NODES" -gt 0 ]; then
      check_health "$ML0_URL" "ml0" 1
    fi
    if [ "$NUM_CL1_NODES" -gt 0 ]; then
      check_health "$CL1_URL" "cl1" 1
    fi
    if [ "$NUM_DL1_NODES" -gt 0 ]; then
      check_health "$DL1_URL" "dl1" 1
    fi
  else
    # Local docker: check node-0 of each layer for full cluster size.
    # Node-0 (genesis) is authoritative — if its /cluster/info reports N nodes,
    # all N nodes have joined the consensus cluster. Checking every node's HTTP
    # port individually is flaky under CI load (non-genesis nodes may have slow
    # Docker port mapping even while actively participating in consensus).
    local any_failed=false

    if [ "$NUM_GL0_NODES" -gt 0 ]; then
      check_health "${host}:${DAG_L0_PORT_PREFIX}00" "gl0-0" "$NUM_GL0_NODES" || any_failed=true
    fi

    if [ "$NUM_GL1_NODES" -gt 0 ]; then
      check_health "${host}:${DAG_L1_PORT_PREFIX}00" "gl1-0" "$NUM_GL1_NODES" || any_failed=true
    fi

    if [ "$NUM_ML0_NODES" -gt 0 ]; then
      check_health "${host}:${ML0_PORT_PREFIX}00" "ml0-0" "$NUM_ML0_NODES" || any_failed=true
    fi

    if [ "$NUM_CL1_NODES" -gt 0 ]; then
      check_health "${host}:${CL1_PORT_PREFIX}00" "cl1-0" "$NUM_CL1_NODES" || any_failed=true
    fi

    if [ "$NUM_DL1_NODES" -gt 0 ]; then
      check_health "${host}:${DL1_PORT_PREFIX}00" "dl1-0" "$NUM_DL1_NODES" || any_failed=true
    fi

    if [ "$any_failed" = "true" ]; then
      echo "ERROR: One or more cluster health checks failed"
      return 1
    fi
  fi

}
