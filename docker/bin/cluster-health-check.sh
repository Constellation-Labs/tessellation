

check_health() {
  local url=$1
  local service=$2
  local num_expected_nodes=$3
  # Poll cluster until it has enough nodes
  retry_count=0
  while [ $retry_count -lt $MAX_RETRIES ]; do
      export CLUSTER_INFO=$(curl -s ${url}/cluster/info) ||  echo "starting"

      # Check if curl returned valid JSON before using jq
      if [ -z "$CLUSTER_INFO" ] || [ "$CLUSTER_INFO" = "null" ] || [ "$CLUSTER_INFO" = "starting" ]; then
        echo "Waiting for $service at $url/cluster/info to come online retry count $retry_count of $MAX_RETRIES"
        sleep 5
        retry_count=$((retry_count+1))
        continue
      fi

      # Use jq with error handling
      CLUSTER_INFO_LEN=$(echo "$CLUSTER_INFO" | jq 'length' 2>/dev/null || echo "0")

      if [ "$CLUSTER_INFO_LEN" -ge "$num_expected_nodes" ]; then
        echo "Success: cluster $service has $CLUSTER_INFO_LEN nodes (>= $num_expected_nodes expected) at $url"
        break
      else
        echo "Waiting for $service at $url to have >= $num_expected_nodes nodes, currently $CLUSTER_INFO_LEN nodes retry $retry_count of $MAX_RETRIES"
        sleep 4
        retry_count=$((retry_count+1))
      fi

      if [ $retry_count -gt $((MAX_RETRIES-2)) ]; then
        echo "ERROR: $service cluster doesn't have >= $num_expected_nodes nodes at $url after $MAX_RETRIES attempts"
        return 1
      fi
    done
}


verify_healthy() {
  echo "Sending cluster poll health request for cluster info to check joined."
  MAX_RETRIES=100
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
    # Local docker: check each node using port prefix pattern
    for i in "0" "1" "2"; do
        l0_url="${host}:${DAG_L0_PORT_PREFIX}${i}0"
        l1_url="${host}:${DAG_L1_PORT_PREFIX}${i}0"
        ml0_url="${host}:${ML0_PORT_PREFIX}${i}0"
        cl1_url="${host}:${CL1_PORT_PREFIX}${i}0"
        dl1_url="${host}:${DL1_PORT_PREFIX}${i}0"

        if [ "$i" -lt "$NUM_GL0_NODES" ]; then
          check_health "$l0_url" "gl0-$i" $NUM_GL0_NODES
        fi

        if [ "$i" -lt "$NUM_GL1_NODES" ]; then
          check_health "$l1_url" "gl1-$i" $NUM_GL1_NODES
        fi

        if [ "$i" -lt "$NUM_ML0_NODES" ]; then
          check_health "$ml0_url" "ml0-$i" $NUM_ML0_NODES
        fi

        if [ "$i" -lt "$NUM_CL1_NODES" ]; then
          check_health "$cl1_url" "cl1-$i" $NUM_CL1_NODES
        fi

        if [ "$i" -lt "$NUM_DL1_NODES" ]; then
          check_health "$dl1_url" "dl1-$i" $NUM_DL1_NODES
        fi

    done
  fi

}
