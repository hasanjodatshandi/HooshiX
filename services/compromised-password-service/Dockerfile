ARG RUNTIME_IMAGE
FROM ${RUNTIME_IMAGE}

ARG APP_JAR=build/libs/compromised-password-service-0.1.0-SNAPSHOT.jar

WORKDIR /app
COPY --chown=10001:10001 ${APP_JAR} /app/application.jar

USER 10001:10001
EXPOSE 9090 9091

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/application.jar"]
