# Snapshot-streaming image: the SS fat-jar baked onto a JRE base.
# application.conf and the data dir are mounted at runtime (env-specific), matching
# docker/snapshot-streaming/docker-compose.yaml — so this image is config-agnostic.
# The jar is produced by docker/snapshot-streaming/build-snapshot-streaming.sh.
FROM eclipse-temurin:21-jre

WORKDIR /app
RUN mkdir -p /app/data
COPY docker/snapshot-streaming/snapshot-streaming.jar /app/snapshot-streaming.jar

# Heap/GC opts are overridable at runtime via SS_JAVA_OPTS (the deploy sets a large ZGC
# profile for testnet). Default stays modest so local/CI runs are unaffected.
ENV SS_JAVA_OPTS="-Xms256m -Xmx1g"
ENTRYPOINT ["sh", "-c", "exec java $SS_JAVA_OPTS --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.security=ALL-UNNAMED -Dconfig.file=/app/application.conf -cp /app/snapshot-streaming.jar org.constellation.snapshotstreaming.App"]
