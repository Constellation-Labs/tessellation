FROM mozilla/sbt:latest as builder

ENV OPENJDK_TAG=8u202
ENV SBT_VERSION=1.5.5

WORKDIR /build

## Download bouncycastle
RUN wget http://www.bouncycastle.org/download/bcprov-jdk15on-169.jar

# Cache dependencies first
COPY project project
COPY modules modules
COPY .scalafix.conf .scalafix.conf
COPY .scalafmt.conf .scalafmt.conf
COPY build.sbt .

# Then build
RUN sbt 'scalafixAll --check --rules OrganizeImports;scalafmtCheckAll;test;it:test;keytool/assembly;core/assembly'

FROM lwieske/java-8 as runner

ENV CL_KEYSTORE=keystore.p12
ENV CL_PASSWORD=1234
ENV CL_KEYPASS=1234
ENV CL_STOREPASS=1234
ENV CL_KEYALIAS=alias

## make sure bouncycastle is setup
COPY --from=builder /build/bcprov-jdk15on-169.jar $JAVA_HOME/jre/lib/ext/bcprov-jdk15on-169.jar
RUN cd $JAVA_HOME/jre/lib/security && \
	echo 'security.provider.10=org.bouncycastle.jce.provider.BouncyCastleProvider' >> $JAVA_HOME/jre/lib/security/java.security

COPY --from=builder /build/modules/core/target/scala-2.13/tesselation-core-assembly-0.0.1.jar /var/lib/constellation/cl-node.jar
COPY --from=builder /build/modules/keytool/target/scala-2.13/tesselation-keytool-assembly-0.0.1.jar /var/lib/constellation/cl-keytool.jar

RUN adduser -S -h /var/lib/constellation constellation
USER constellation
WORKDIR /var/lib/constellation/

# Generate keystore
RUN java -jar cl-keytool.jar generate

EXPOSE 9000
EXPOSE 9001
EXPOSE 9002
