

check_health() {
  local port=$1
  local i=$2
  local service=$3
  # Poll L0 cluster until it has 3 nodes
  retry_count=0
  while [ $retry_count -lt $MAX_RETRIES ]; do
      # echo "Checking cluster on port ${port} (attempt $((retry_count+1))/$MAX_RETRIES)"
      export CLUSTER_INFO=$(curl -s http://localhost:${port}/cluster/info) ||  echo "starting"
      # echo "CLUSTER_INFO: $CLUSTER_INFO for $port"
      
      # Check if curl returned valid JSON before using jq
      if [ -z "$CLUSTER_INFO" ] || [ "$CLUSTER_INFO" = "null" ] || [ "$CLUSTER_INFO" = "starting" ]; then
        echo "Waiting for $service node $i on port $port/cluster/info to come online retry count $retry_count of $MAX_RETRIES"
        sleep 5
        retry_count=$((retry_count+1))
        continue
      fi
      
      # Use jq with error handling
      CLUSTER_INFO_LEN=$(echo "$CLUSTER_INFO" | jq 'length' 2>/dev/null || echo "0")
      # echo "CLUSTER_INFO_LEN: $CLUSTER_INFO_LEN"
      
      if [ "$CLUSTER_INFO_LEN" = "3" ]; then
        echo "Success: cluster $service $i has 3 nodes on port $port"
        break
      else
        echo "Waiting for $service node $i on port $port to have 3 nodes, currently $CLUSTER_INFO_LEN nodes retry $retry_count of $MAX_RETRIES"
        sleep 4
        retry_count=$((retry_count+1))
      fi
      
      if [ $retry_count -gt $((MAX_RETRIES-2)) ]; then
        echo "ERROR: $service cluster $i doesn't have 3 nodes on port $port after $MAX_RETRIES attempts"
        return 1
      fi
    done
}


verify_healthy() {
  echo "Sending cluster poll health request for cluster info to check joined."
  MAX_RETRIES=100
  for i in "0" "1" "2"; do
      l0_port="${DAG_L0_PORT_PREFIX}${i}0"
      l1_port="${DAG_L1_PORT_PREFIX}${i}0"
      ml0_port="${ML0_PORT_PREFIX}${i}0"
      cl1_port="${CL1_PORT_PREFIX}${i}0"
      dl1_port="${DL1_PORT_PREFIX}${i}0"

      if [ "$i" -lt "$NUM_GL0_NODES" ]; then
        check_health $l0_port $i "gl0"
      fi

      if [ "$i" -lt "$NUM_GL1_NODES" ]; then
        check_health $l1_port $i "gl1"
      fi

      if [ "$i" -lt "$NUM_ML0_NODES" ]; then
        check_health $ml0_port $i "ml0"
      fi

      if [ "$i" -lt "$NUM_CL1_NODES" ]; then
        check_health $cl1_port $i "cl1"
      fi
      
      if [ "$i" -lt "$NUM_DL1_NODES" ]; then
        check_health $dl1_port $i "dl1"
      fi
      
  done

}
