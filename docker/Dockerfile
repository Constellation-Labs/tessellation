FROM ubuntu:22.04

RUN apt update && apt install -y openjdk-11-jre

# Dev tools
RUN apt install -y curl iproute2 iputils-ping jq

RUN mkdir -p /tessellation/jars

COPY docker/jars/keytool.jar /tessellation/jars/keytool.jar
COPY docker/jars/wallet.jar /tessellation/jars/wallet.jar

COPY docker/jars/gl1.jar /tessellation/jars/gl1.jar
COPY docker/jars/gl0.jar /tessellation/jars/gl0.jar

COPY docker/jars/ml0.jar /tessellation/jars/ml0.jar
COPY docker/jars/dl1.jar /tessellation/jars/dl1.jar
COPY docker/jars/cl1.jar /tessellation/jars/cl1.jar

COPY docker/entrypoint.sh /tessellation/entrypoint.sh
COPY docker/internal-health-check.sh /tessellation/internal-health-check.sh
COPY docker/health-check-inner.sh /tessellation/health-check-inner.sh
RUN chmod +x /tessellation/entrypoint.sh
RUN chmod +x /tessellation/internal-health-check.sh
RUN chmod +x /tessellation/health-check-inner.sh
WORKDIR /tessellation

ENV CL_KEYSTORE=/tessellation/key.p12
ENV CL_COLLATERAL=25000000000000
ENV CL_KEYALIAS="alias"
ENV CL_DEFAULT="password"
ENV CL_APP_ENV="mainnet"


ENTRYPOINT ["/tessellation/entrypoint.sh"]
