#!/usr/bin/env bash

set -e


# 2. More thorough container cleanup with proper error handling
echo "Stopping and removing gl0 containers..."


cleanup_container() {
    local name=$1
    local vol=$2
    docker stop $name 2>/dev/null || true
    docker rm -f $name 2>/dev/null || true
    docker volume rm ${vol} 2>/dev/null || true
}

cleanup() {
    for i in 0 1 2; do
        cleanup_container gl0-$i gl0-data-$i &
        cleanup_container gl1-$i gl1-data-$i &
    done
    LAST_PID=$!
    wait $LAST_PID
}

cleanup &
export CLEANUP_PID=$!
# 8. Remove the network with better error handling and retry logic
echo "Removing tessellation_common network..."
while true; do
  output=$(docker network rm tessellation_common 2>&1) || true
  if [[ $output == *"not found"* ]]; then
    echo "Network removed successfully"
    break
  elif [[ $output != *"has active endpoints"* ]]; then
    # If the error message is not present, break the loop
    echo "Network removed successfully or encountered a different error. Output below"
    echo $output
    break
  fi
  echo "Network has active endpoints, retrying in 1 second..."
  sleep 1
done

echo "Waiting for cleanup to finish..."
wait $CLEANUP_PID

# 3. Find and kill any lingering processes binding to tessellation ports

check_port_binds() {
    if [ "$CHECK_PORT_BINDS" == "true" ]; then
        echo "Checking for lingering processes on common ports... -- this requires sudo"
        for base_port in 9000 9001 9002 9010 9011 9012; do
            for prefix in "" "1" "2"; do
                port="${prefix}${base_port}"
                pid=$(sudo lsof -i:$port -t 2>/dev/null || true)
                if [ -n "$pid" ]; then
                    echo "Found process $pid on port $port"
                    return 0
                fi
            done
        done
    fi
}

check_port_binds