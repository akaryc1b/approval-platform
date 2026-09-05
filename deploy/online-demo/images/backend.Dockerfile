# The wrapper supplies digest-pinned public bases; no mutable defaults.
ARG MAVEN_IMAGE
ARG JAVA_IMAGE
FROM ${MAVEN_IMAGE} AS backend-build
ARG APP_VERSION
ARG SOURCE_DATE_EPOCH
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -Pproduct-readiness-demo -Drevision="${APP_VERSION}" \
      -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" -DskipTests clean install \
    && mvn -B -ntp -Pproduct-readiness-demo -Drevision="${APP_VERSION}" \
      -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" -DskipTests \
      -pl :approval-server package spring-boot:repackage \
    && mkdir /out \
    && cp "apps/server/target/approval-server-${APP_VERSION}.jar" /out/app.jar \
    && sh deploy/online-demo/images/verify-boot-jar.sh /out/app.jar \
    && cd /out && sha256sum app.jar > app.jar.sha256

FROM ${JAVA_IMAGE} AS backend
ARG APP_VERSION
ARG SOURCE_COMMIT
ARG SOURCE_TREE
ARG SOURCE_ARCHIVE_SHA256
LABEL org.opencontainers.image.title="Approval Platform evaluation backend" \
      org.opencontainers.image.source="https://github.com/akaryc1b/approval-platform" \
      org.opencontainers.image.revision="${SOURCE_COMMIT}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      io.approval.source.tree="${SOURCE_TREE}" \
      io.approval.source.archive="${SOURCE_ARCHIVE_SHA256}" \
      io.approval.component="backend" \
      io.approval.scope="packaging-only-not-online-accepted"
RUN java -version 2>&1 | grep 'version "21\.'
WORKDIR /app
COPY --from=backend-build --chown=10001:10001 /out/ /app/
COPY --chown=10001:10001 LICENSE NOTICE /opt/approval/
USER 10001:10001
# Private packaging default, NOT a deployable online-demo security profile.
# A later tested isolation package must explicitly configure private connectivity.
ENV SERVER_ADDRESS=127.0.0.1
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
