#!/bin/bash

set -e
sbt clean
sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly

mkdir -p ./docker/jars/
# Note this copy command may fail if you recompile without clean due to the *dirty* suffix, fixable with env
# Duplicate copy overwrites with dirty version if only compiling one module
# Order is deliberate here for reruns

for module in "dag-l0" "gl1" "keytool" "wallet"
do 
    # Note this intended to work with *dirty suffixes as well, if you need to enforce exactness use 
    # a different script.
    path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
    cp $path ./docker/jars/${module}.jar
done


docker build -t constellationnetwork/tessellation:test -f docker/Dockerfile .