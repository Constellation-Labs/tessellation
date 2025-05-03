#!/bin/bash

set -e
sbt clean
sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly

docker build -t constellationnetwork/tessellation:test -f docker/Dockerfile .