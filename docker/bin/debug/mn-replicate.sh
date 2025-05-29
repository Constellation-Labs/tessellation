
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

PROJECT_ROOT=$(cd "$SCRIPT_DIR/../../../" && pwd)


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

ordinal=$(ssh $REMOTE_SOURCE_NODE "bash -c 'cd /home/admin/tessellation/l0/data/snapshot_info && ls -t | head -n 1'")

echo "Found latest ordinal: $ordinal"

latest_ordinal=$ordinal
ordinal=$((latest_ordinal - 1))


# commit your current changes
git add .
git commit -m "Replicated snapshot $ordinal"
git push



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
ssh $REMOTE_DESTINATION_NODE "bash -c \"rm -rf $l0_data\""
ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/$storage_path\""
ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/incremental_snapshot/ordinal/${block}\""
ssh $REMOTE_DESTINATION_NODE "bash -c \"mkdir -p $l0_data/snapshot_info\""

scp incremental-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/$storage_path_full
scp incremental-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/incremental_snapshot/ordinal/${block}/$ordinal
scp info-$ordinal $REMOTE_DESTINATION_NODE:$l0_data/snapshot_info/$ordinal

# cleanup
rm incremental-$ordinal
rm info-$ordinal

# run the compose-runner
# capture your local branch name
cur_branch=$(git branch --show-current)

cat > bootstrap.sh <<EOF
apt install -y just
mkdir -p ~/projects
cd ~/projects

if [ ! -d tessellation ]; then
git clone https://github.com/Constellation-Labs/tessellation.git
fi

cd tessellation
git fetch origin
git checkout -B $cur_branch origin/$cur_branch
git pull --force

just build --version=$RELEASE_TAG
rm /root/docker/docker-compose.yaml;
cp /root/projects/tessellation/docker/docker-compose.yaml /root/docker/docker-compose.yaml
cd /root/docker;
docker compose down;
docker compose --profile l0 up -d
EOF

scp bootstrap.sh $REMOTE_DESTINATION_NODE:~/bootstrap.sh
ssh $REMOTE_DESTINATION_NODE "bash -c \"chmod +x ~/bootstrap.sh\""
ssh $REMOTE_DESTINATION_NODE "bash -c \"~/bootstrap.sh\""

rm bootstrap.sh