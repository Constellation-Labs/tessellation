#!/usr/bin/env bash

set -e


# 2. More thorough container cleanup with proper error handling
echo "Stopping and removing global-l0 containers..."
docker ps -a --filter name=global-l0 --format "{{.ID}}" | while read -r container_id; do
    docker stop "$container_id" 2>/dev/null || true
    docker rm -f "$container_id" 2>/dev/null || true
done

echo "Stopping and removing dag-l1 containers..."
docker ps -a --filter name=dag-l1 --format "{{.ID}}" | while read -r container_id; do
    docker stop "$container_id" 2>/dev/null || true
    docker rm -f "$container_id" 2>/dev/null || true
done

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

# 4. Clean containers on the tessellation network with proper error handling
echo "Removing containers on tessellation_common network..."
containers=$(docker ps -a --filter network=tessellation_common --format "{{.ID}}" 2>/dev/null || echo "")
if [ -n "$containers" ]; then
    echo "$containers" | while read -r container_id; do
        docker stop "$container_id" 2>/dev/null || true
        docker rm -f "$container_id" 2>/dev/null || true
    done
fi

# 6. Remove volumes with better error handling
echo "Removing tessellation volumes..."
for vol in gl0-data dag-l1-data; do
    for suffix in "-0" "-1" "-2"; do
        vol="${vol}${suffix}"
        docker volume rm $vol 2>/dev/null || true
    done
done

# 7. Force cleanup any dangling volumes that match our pattern
echo "Cleaning up any dangling volumes..."
docker volume ls -qf dangling=true | grep -E 'gl0-data|dag-l1-data' | xargs -r docker volume rm 2>/dev/null || true

# 8. Remove the network with better error handling
echo "Removing tessellation_common network..."
docker network rm tessellation_common 2>/dev/null || true

