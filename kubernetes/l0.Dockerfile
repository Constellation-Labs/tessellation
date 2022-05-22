# syntax=docker/dockerfile:1.4
FROM amazoncorretto:11-alpine

WORKDIR /app

RUN apk --no-cache add curl

COPY kubernetes/data/l0-initial-validator-key.p12 kubernetes/data/genesis.csv kubernetes/await-join.sh kubernetes/await.sh ./

RUN cat <<EOF > start.sh
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_APP_ENV=dev

if [ -n "\$INITIAL_VALIDATOR" ]
then
    export CL_KEYSTORE=l0-initial-validator-key.p12
    java -jar core.jar run-genesis /app/genesis.csv
else
    export CL_KEYSTORE=key.p12
    java -jar keytool.jar generate
    ./await-join.sh \
        \$L0_INITIAL_VALIDATOR_SERVICE_HOST \
        \$L0_INITIAL_VALIDATOR_SERVICE_PORT_PUBLIC \
        \$L0_INITIAL_VALIDATOR_SERVICE_PORT_P2P \
        \$L0_INITIAL_VALIDATOR_ID &
    java -jar core.jar run-validator
fi
EOF
RUN chmod +x start.sh

EXPOSE 9000 9001 9002

COPY modules/keytool/target/scala-2.13/tessellation-keytool-assembly*.jar keytool.jar
COPY modules/core/target/scala-2.13/tessellation-core-assembly*.jar core.jar

CMD ["/bin/sh", "-c", "/app/start.sh"]