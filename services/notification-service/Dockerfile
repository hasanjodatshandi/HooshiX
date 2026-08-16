ARG RUNTIME_BASE_IMAGE
FROM ${RUNTIME_BASE_IMAGE}

ARG APP_JAR=build/libs/notification-service-0.1.0-SNAPSHOT.jar
ARG RUNTIME_JDK_DIR=.runtime-jdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

WORKDIR /app
COPY --chown=10001:10001 ${RUNTIME_JDK_DIR}/ /opt/java/openjdk/
COPY --chown=10001:10001 ${APP_JAR} /app/application.jar

USER 10001:10001
EXPOSE 9090 9092

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/application.jar"]
