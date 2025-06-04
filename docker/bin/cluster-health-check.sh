
verify_healthy() {
  echo "Sending cluster poll health request for cluster info to check joined."
  MAX_RETRIES=30
  for i in "0" "1" "2"; do
      l0_port="${DAG_L0_PORT_PREFIX}${i}0"
      l1_port="${DAG_L1_PORT_PREFIX}${i}0"
      for port in $l0_port $l1_port; do

        # Poll L0 cluster until it has 3 nodes
        retry_count=0
        while [ $retry_count -lt $MAX_RETRIES ]; do
            # echo "Checking cluster on port ${port} (attempt $((retry_count+1))/$MAX_RETRIES)"
            export CLUSTER_INFO=$(curl -s http://localhost:${port}/cluster/info) ||  echo "starting"
            # echo "CLUSTER_INFO: $CLUSTER_INFO for $port"
            
            # Check if curl returned valid JSON before using jq
            if [ -z "$CLUSTER_INFO" ] || [ "$CLUSTER_INFO" = "null" ] || [ "$CLUSTER_INFO" = "starting" ]; then
              echo "Waiting for node $i on port $port/cluster/info to come online"
              sleep 4
              retry_count=$((retry_count+1))
              continue
            fi
            
            # Use jq with error handling
            CLUSTER_INFO_LEN=$(echo "$CLUSTER_INFO" | jq 'length' 2>/dev/null || echo "0")
            # echo "CLUSTER_INFO_LEN: $CLUSTER_INFO_LEN"
            
            if [ "$CLUSTER_INFO_LEN" = "3" ]; then
              echo "Success: cluster $i has 3 nodes on port $port"
              break
            else
              echo "Waiting for node $i on port $port to have 3 nodes, currently has $CLUSTER_INFO_LEN nodes"
              sleep 4
              retry_count=$((retry_count+1))
            fi
            
            if [ $retry_count -eq $MAX_RETRIES ]; then
              echo "ERROR: cluster $i doesn't have 3 nodes on port $port after $MAX_RETRIES attempts"
              return 1
            fi
          done
      done
  done

}
