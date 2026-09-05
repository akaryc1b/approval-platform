ARG NODE_IMAGE
ARG NGINX_IMAGE
FROM ${NODE_IMAGE} AS client-tools
WORKDIR /workspace
COPY . .
RUN node -e 'const [m,n]=process.versions.node.split(".").map(Number); if(!((m===22&&n>=18)||m===24)) process.exit(1)' \
    && git --version \
    && corepack enable \
    && corepack prepare "$(node -p 'require("./package.json").packageManager')" --activate \
    && pnpm install --frozen-lockfile
# No development identity, role token, tenant or secret is baked into either client.
ENV VITE_APPROVAL_LOCAL_DEMO=false \
    VITE_APPROVAL_CONNECTOR=standalone \
    VITE_APPROVAL_API_URL=/api \
    VITE_APPROVAL_H5_API_URL=/api \
    VITE_APP_PROXY_ENABLE=false \
    VITE_NITRO_MOCK=false

FROM client-tools AS pc-build
ARG SOURCE_COMMIT
ARG SOURCE_TREE
ARG SOURCE_DATE_EPOCH
RUN node scripts/upstream/bootstrap-vben.mjs \
    && pnpm --dir .upstream/vben install --frozen-lockfile \
    && pnpm --dir .upstream/vben build:ele \
    && mkdir /out \
    && node scripts/product-readiness/online-demo/static-artifacts.mjs pc \
      .upstream/vben/apps/web-ele/dist .upstream/vben/pnpm-lock.yaml /out/pc

FROM client-tools AS h5-build
ARG SOURCE_COMMIT
ARG SOURCE_TREE
ARG SOURCE_DATE_EPOCH
# Preserve the existing mobile installation policy. The resolved lock is retained;
# this is explicitly NOT a claim of bit-reproducible H5 dependency resolution.
# Bootstrap once: a second bootstrap would reset the resolved lock after install.
RUN node scripts/upstream/bootstrap-unibest.mjs \
    && pnpm -C .upstream/unibest install --no-frozen-lockfile \
    && pnpm -C .upstream/unibest init-baseFiles \
    && pnpm -C .upstream/unibest build:h5 \
    && mkdir /out \
    && node scripts/product-readiness/online-demo/static-artifacts.mjs h5 \
      .upstream/unibest/dist/build/h5 .upstream/unibest/pnpm-lock.yaml /out/h5

FROM ${NGINX_IMAGE} AS static-runtime
ARG APP_VERSION
ARG SOURCE_COMMIT
ARG SOURCE_TREE
ARG SOURCE_ARCHIVE_SHA256
LABEL org.opencontainers.image.source="https://github.com/akaryc1b/approval-platform" \
      org.opencontainers.image.revision="${SOURCE_COMMIT}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      io.approval.source.tree="${SOURCE_TREE}" \
      io.approval.source.archive="${SOURCE_ARCHIVE_SHA256}" \
      io.approval.scope="static-packaging-only-not-online-accepted"
COPY deploy/online-demo/images/nginx.conf /etc/nginx/nginx.conf
COPY LICENSE NOTICE /opt/approval/
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]

FROM static-runtime AS pc
LABEL io.approval.component="pc"
COPY --from=pc-build /out/pc/public/ /app/public/
COPY --from=pc-build /out/pc/build-info.json /out/pc/resolved-pnpm-lock.yaml /opt/approval/
COPY --from=pc-build /workspace/.upstream/vben/LICENSE /opt/approval/UPSTREAM-LICENSE

FROM static-runtime AS h5
LABEL io.approval.component="h5"
COPY --from=h5-build /out/h5/public/ /app/public/
COPY --from=h5-build /out/h5/build-info.json /out/h5/resolved-pnpm-lock.yaml /opt/approval/
COPY --from=h5-build /workspace/.upstream/unibest/LICENSE /opt/approval/UPSTREAM-LICENSE
