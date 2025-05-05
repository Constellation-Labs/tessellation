FROM ubuntu:22.04

RUN apt update && apt install -y openjdk-11-jre

# Dev tools
RUN apt install -y curl iproute2 iputils-ping jq

RUN mkdir -p /tessellation/jars

COPY modules/keytool/target/scala-2.13/tessellation-keytool-assembly-*.jar /tessellation/jars/keytool.jar
COPY modules/wallet/target/scala-2.13/tessellation-wallet-assembly-*.jar /tessellation/jars/wallet.jar
COPY modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly-*.jar /tessellation/jars/dag-l1.jar
COPY modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly-*.jar /tessellation/jars/dag-l0.jar

COPY docker/entrypoint.sh /tessellation/entrypoint.sh
RUN chmod +x /tessellation/entrypoint.sh

COPY docker/entrypoint-dag-l1-join-coordinator.sh /tessellation/entrypoint-dag-l1-join-coordinator.sh
RUN chmod +x /tessellation/entrypoint-dag-l1-join-coordinator.sh

EXPOSE 9000 9001 9010 9011

WORKDIR /tessellation

ENV CL_KEYSTORE=/tessellation/key.p12
ENV CL_COLLATERAL=25000000000000
ENV CL_KEYALIAS="alias"
ENV CL_DEFAULT="password"
ENV CL_APP_ENV="mainnet"
ENV CL_L0_PEER_HTTP_PORT=9000


ENTRYPOINT ["/tessellation/entrypoint.sh"]
