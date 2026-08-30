ARG CADDY_RUNTIME_IMAGE=docker.io/library/caddy@sha256:df7f1c2fb114453b951de51a98efc010db1655a92c2e86be6706714e2417a78d
FROM ${CADDY_RUNTIME_IMAGE}
RUN setcap -r /usr/bin/caddy || true
COPY Caddyfile /etc/caddy/Caddyfile
COPY --chown=10001:10001 dist/ /srv/
ENV XDG_CONFIG_HOME=/tmp/config XDG_DATA_HOME=/tmp/data
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["caddy","run","--config","/etc/caddy/Caddyfile","--adapter","caddyfile"]
