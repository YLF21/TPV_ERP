FROM maven:3-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /workspace
COPY backend-saas/pom.xml backend-saas/mvnw backend-saas/mvnw.cmd ./backend-saas/
COPY backend-saas/.mvn ./backend-saas/.mvn
RUN mvn -f backend-saas/pom.xml -B -q dependency:go-offline
COPY backend-saas/src ./backend-saas/src
RUN mvn -f backend-saas/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/tpv-saas/backup-vault \
    && chown -R 10001:10001 /var/lib/tpv-saas \
    && chmod 700 /var/lib/tpv-saas/backup-vault
COPY --from=build /workspace/backend-saas/target/tpv-erp-saas-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
