#!/usr/bin/env bash

set -e

mkdir -p /tmp/constellation
wget https://constellationlabs-dag.s3.amazonaws.com/cluster/dag-$1.jar -q -O /tmp/constellation/dag.jar
wget https://constellationlabs-dag.s3.amazonaws.com/cluster/dagl1-$1.jar -q -O /tmp/constellation/dagl1.jar
wget https://constellationlabs-dag.s3.amazonaws.com/keys_v2/key-"$2"_v2.p12 -q -O /tmp/constellation/key.p12
wget https://constellationlabs-dag.s3.amazonaws.com/keys_v2/data.csv -q -O /tmp/constellation/data.csv

# take external host ip
curl http://checkip.amazonaws.com > /tmp/constellation/external_host_ip

cp /tmp/start_node /tmp/constellation/start_node
cp /tmp/start_genesis /tmp/constellation/start_genesis
cp /tmp/start_rollback /tmp/constellation/start_rollback
cp /tmp/constellation/start_node /tmp/constellation/start
cp /tmp/logback.xml /tmp/constellation/logback.xml

sudo mv /tmp/constellation/* /home/admin/constellation
sudo chmod u+x /home/admin/constellation/start_node
sudo chmod u+x /home/admin/constellation/start_genesis
sudo chmod u+x /home/admin/constellation/start_rollback
sudo chmod u+x /home/admin/constellation/start
sudo chown -R admin:admin /home/admin/constellation

sudo systemctl daemon-reload
sudo systemctl enable constellation.service
