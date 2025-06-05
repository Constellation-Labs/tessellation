#!/usr/bin/env bash

ID=$CL_DOCKER_ID

if [ "$ID" == "gl0" ]; then
  export PUBLIC_PORT=$CL_DOCKER_INTERNAL_GL0_PUBLIC
fi

if [ "$ID" == "gl1" ]; then
  export PUBLIC_PORT=$CL_DOCKER_INTERNAL_GL1_PUBLIC
fi

if [ "$ID" == "ml0" ]; then
  export PUBLIC_PORT=$CL_DOCKER_INTERNAL_ML0_PUBLIC
fi

if [ "$ID" == "ml1" ]; then
  export PUBLIC_PORT=$CL_DOCKER_INTERNAL_ML1_PUBLIC
fi

if [ "$ID" == "dl1" ]; then
  export PUBLIC_PORT=$CL_DOCKER_INTERNAL_DL1_PUBLIC
fi

echo "Checking health on port $PUBLIC_PORT with id $ID"

exec curl -f "http://localhost:${PUBLIC_PORT}/node/health"