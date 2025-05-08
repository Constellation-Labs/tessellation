FROM ubuntu:22.04

RUN apt update && apt install -y openjdk-11-jre

# Dev tools
RUN apt install -y curl iproute2 iputils-ping jq

RUN mkdir -p /tessellation/jars

COPY docker/jars/keytool.jar /tessellation/jars/keytool.jar
COPY docker/jars/wallet.jar /tessellation/jars/wallet.jar
COPY docker/jars/dag-l1.jar /tessellation/jars/dag-l1.jar
COPY docker/jars/dag-l0.jar /tessellation/jars/dag-l0.jar

COPY docker/entrypoint.sh /tessellation/entrypoint.sh
RUN chmod +x /tessellation/entrypoint.sh

COPY docker/entrypoint-dag-l1-join-coordinator.sh /tessellation/entrypoint-dag-l1-join-coordinator.sh
RUN chmod +x /tessellation/entrypoint-dag-l1-join-coordinator.sh

COPY docker/entrypoint-dag-l0-join-coordinator.sh /tessellation/entrypoint-dag-l0-join-coordinator.sh
RUN chmod +x /tessellation/entrypoint-dag-l0-join-coordinator.sh

EXPOSE 9000 9001 9010 9011

WORKDIR /tessellation

ENV CL_KEYSTORE=/tessellation/key.p12
ENV CL_COLLATERAL=25000000000000
ENV CL_KEYALIAS="alias"
ENV CL_DEFAULT="password"
ENV CL_APP_ENV="mainnet"
ENV CL_L0_PEER_HTTP_PORT=9000


ENTRYPOINT ["/tessellation/entrypoint.sh"]
