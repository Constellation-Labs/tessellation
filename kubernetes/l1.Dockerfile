# syntax=docker/dockerfile:1.4
FROM amazoncorretto:11-alpine
ARG BUILD_VERSION

WORKDIR /app

RUN apk --no-cache add curl

COPY kubernetes/data/l1-initial-validator-key.p12 kubernetes/await-join.sh kubernetes/await.sh ./

RUN cat <<EOF > start.sh
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_APP_ENV=dev
export CL_L0_PEER_ID=\$L0_INITIAL_VALIDATOR_ID
export CL_L0_PEER_HTTP_HOST=\$L0_INITIAL_VALIDATOR_SERVICE_HOST
export CL_L0_PEER_HTTP_PORT=\$L0_INITIAL_VALIDATOR_SERVICE_PORT_PUBLIC

CURL_RETRY_CMD="curl --retry 60 --retry-delay 1 --retry-all-errors --fail --silent"

if [ -n "\$INITIAL_VALIDATOR" ]
then
    export CL_KEYSTORE=l1-initial-validator-key.p12
    ./await.sh \$L0_INITIAL_VALIDATOR_SERVICE_HOST \$L0_INITIAL_VALIDATOR_SERVICE_PORT_PUBLIC && \
        java -jar dag-l1.jar run-initial-validator
else
    export CL_KEYSTORE=key.p12
    java -jar keytool.jar generate
    ./await.sh \$L0_INITIAL_VALIDATOR_SERVICE_HOST \$L0_INITIAL_VALIDATOR_SERVICE_PORT_PUBLIC && \
        (
            ./await-join.sh \
                \$L1_INITIAL_VALIDATOR_SERVICE_HOST \
                \$L1_INITIAL_VALIDATOR_SERVICE_PORT_PUBLIC \
                \$L1_INITIAL_VALIDATOR_SERVICE_PORT_P2P \
                \$L1_INITIAL_VALIDATOR_ID &
            java -jar dag-l1.jar run-validator
        )
fi
EOF
RUN chmod +x start.sh

EXPOSE 9000 9001 9002

COPY modules/keytool/target/scala-2.13/tessellation-keytool-assembly-${BUILD_VERSION}.jar keytool.jar
COPY modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly-${BUILD_VERSION}.jar dag-l1.jar

CMD ["/bin/sh", "-c", "/app/start.sh"]