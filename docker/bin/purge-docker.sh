#!/usr/bin/env bash
set -e

docker stop $(docker ps -a -q) && \
docker rm $(docker ps -a -q) && \
docker volume rm $(docker volume ls -q) && \
docker network rm $(docker network ls -q)
