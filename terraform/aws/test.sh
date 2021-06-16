#!/bin/bash

export HOSTS_FILE='./hosts-kpudlik'

# Read hosts to array
hosts=()
while read ip; do
  hosts+=("$ip")
  echo "Found peer $ip"
done <$HOSTS_FILE

# Join
for host in "${hosts[@]}"
do
  echo ""
  echo "Joining $host to facilitators"
  for facilitator in "${hosts[@]}"
  do
    if [ "$host" == "$facilitator" ]; then
      echo "Skipping self join for $host"
    else
      echo "Join $host -> $facilitator"
    fi
  done
done


