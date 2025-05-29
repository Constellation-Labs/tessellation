
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

PROJECT_ROOT=$(cd "$SCRIPT_DIR/../../../" && pwd)

echo "PROJECT_ROOT: $PROJECT_ROOT"

if [ -z $REMOTE_SOURCE_NODE ]; then
    export REMOTE_SOURCE_NODE=genesis
fi

if [ -z $REMOTE_DESTINATION_NODE ]; then
    export REMOTE_DESTINATION_NODE=dest
fi

if [ -z $SOURCE_HTTP ]; then
    export SOURCE_HTTP="http://alnitak-node.constellationnetwork.io"
fi

if [ -z $RELEASE_TAG ]; then
    export RELEASE_TAG="v3.2.0"
fi


ssh $REMOTE_DESTINATION_NODE "bash -c \"docker exec -it gl0 bash -c 'curl -X POST http://localhost:9002/cluster/leave'\"" || true;
ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p /root/projects\""
ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p /root/docker\""

wget https://github.com/Constellation-Labs/tessellation/releases/download/$RELEASE_TAG/mainnet-seedlist -O mainnet-seedlist
scp mainnet-seedlist $REMOTE_DESTINATION_NODE:/root/docker/mainnet-seedlist
rm mainnet-seedlist

cd $PROJECT_ROOT
rsync -avzP \
  --filter=':- .gitignore' \
  --exclude='.git/' \
  . $REMOTE_DESTINATION_NODE:/root/projects/tessellation

echo "Rsync command  $PROJECT_ROOT $REMOTE_DESTINATION_NODE:/root/projects/tessellation" 

cat > bootstrap.sh <<EOF
apt install -y just
rm -rf /root/docker/l0/logs || true;
rm -rf /root/docker/l0/data || true;
mkdir -p /root/docker/l0/logs
mkdir -p /root/docker/l0/data
cd ~/projects
cd tessellation
source /root/.bashrc || true;
just build --version=$RELEASE_TAG
just purge-docker
cp /root/projects/tessellation/docker/docker-compose.yaml /root/docker/docker-compose.yaml
cd /root/docker;
cp /root/.env .env
cat /root/.env.remote >> .env
chmod +x /root/projects/tessellation/docker/bin/debug/get-ip.sh
/root/projects/tessellation/docker/bin/debug/get-ip.sh;
docker compose down --remove-orphans || true;
docker compose rm || true;
EOF


response=$(curl -s $SOURCE_HTTP:9001/registration/request)
VERSION_HASH=$(echo "$response" | jq -r '.version')
JAR_HASH=$(echo "$response" | jq -r '.jar')

cat > .env.remote <<EOF
CL_APP_ENV=mainnet
CL_KEYSTORE_MOUNT_PATH="/root/key.p12"
CL_DOCKER_BIND_INTERFACE="0.0.0.0:"
CL_DAG_L0_JOIN_ENABLED=true
CL_DAG_L0_JOIN_IP=52.53.46.33
CL_DAG_L0_JOIN_ID=e0c1ee6ec43510f0e16d2969a7a7c074a5c8cdb477c074fe9c32a9aad8cbc8ff1dff60bb81923e0db437d2686a9b65b86c403e6a21fa32b6acc4e61be4d70925
CL_DAG_L0_JOIN_PORT=9001
CL_L0_DATA_MOUNT_PATH=/root/docker/l0/data
CL_DOCKER_CLI_BIND_INTERFACE="127.0.0.1:"
TESSELLATION_DOCKER_VERSION=test
CL_VERSION_HASH=$VERSION_HASH
CL_JAR_HASH=$JAR_HASH
CL_DOCKER_JAVA_OPTS="-Xms1024M -Xmx12G -Xss256K"
CL_DOCKER_SEEDLIST=/root/docker/mainnet-seedlist
CL_DOCKER_LOGS=/root/docker/l0/logs
L0_CL_CLI_HTTP_PORT=9002
CL_DOCKER_L0_JOIN_RETRIES=1
CL_DOCKER_L0_JOIN_RETRY_DELAY=100
EOF

#  -Dcats.effect.tracing.mode=full -Dcats.effect.tracing.buffer.size=1024 -Dcats.effect.tracing.exceptions.enhanced=true"

scp .env.remote $REMOTE_DESTINATION_NODE:/root/.env.remote
rm .env.remote
scp bootstrap.sh $REMOTE_DESTINATION_NODE:~/bootstrap.sh
ssh $REMOTE_DESTINATION_NODE "bash -c \"chmod +x ~/bootstrap.sh; ~/bootstrap.sh\""

rm bootstrap.sh


hotload_data() {

    ordinal=$(ssh $REMOTE_SOURCE_NODE "bash -c 'cd /home/admin/tessellation/l0/data/snapshot_info && ls -t | head -n 1'")

    echo "Found latest ordinal: $ordinal"

    latest_ordinal=$ordinal
    ordinal=$((latest_ordinal - 1))

    scp $REMOTE_SOURCE_NODE:/home/admin/tessellation/l0/data/snapshot_info/$ordinal info-$ordinal

    # compute the floor-to-20k block
    block=$(( ordinal / 20000 * 20000 ))

    # build the full path
    path="/home/admin/tessellation/l0/data/incremental_snapshot/ordinal/${block}/${ordinal}"

    # Example: /home/admin/tessellation/l0/data/incremental_snapshot/ordinal/4460000/4460741

    echo "$path"

    scp $REMOTE_SOURCE_NODE:$path incremental-$ordinal

    echo "Downloaded snapshot incremental: $ordinal"

    # export ordinal=4460741
    export ordinal_plus_one=$((ordinal + 1))

    response=$(curl -s $SOURCE_HTTP:9000/global-snapshots/$ordinal_plus_one)
    last_snapshot_hash=$(echo "$response" | jq -r '.value.lastSnapshotHash')

    echo "Last snapshot hash: $last_snapshot_hash"

    storage_path="incremental_snapshot/hash/${last_snapshot_hash:0:3}/${last_snapshot_hash:3:3}"    
    storage_path_full="$storage_path/${last_snapshot_hash}"

    l0_data="/root/docker/l0/data"
    l0_logs="/root/docker/l0/logs"
    ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/$storage_path\""
    ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/incremental_snapshot/ordinal/${block}\""
    ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/snapshot_info\""

    scp incremental-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/$storage_path_full
    scp incremental-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/incremental_snapshot/ordinal/${block}/$ordinal
    scp info-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/snapshot_info/$ordinal

    # cleanup
    rm incremental-$ordinal
    rm info-$ordinal

}

ssh $REMOTE_DESTINATION_NODE "bash -c \"cd /root/docker; docker compose --profile l0 up -d \""
sleep 30
ssh $REMOTE_DESTINATION_NODE "bash -c \"docker logs gl0\""

hotload_data

ssh $REMOTE_DESTINATION_NODE "bash -c \"tail -f /root/docker/l0/logs/app.log\""










# # example
# rsync -avP \
#   --filter=':- .gitignore' \
#   --exclude='.git/' \
#   . dest:/root/projects/tessellation


# Alternative deploy here:

# commit your current changes (if desired)
# git add .
# git commit -m "Replicated snapshot $ordinal"
# git push

# run the compose-runner
# capture your local branch name
# cur_branch=$(git branch --show-current)

# git fetch origin
# git checkout -B $cur_branch origin/$cur_branch
# git pull --force
# mkdir -p ~/projects
# if [ ! -d tessellation ]; then
# git clone https://github.com/Constellation-Labs/tessellation.git
# fi

