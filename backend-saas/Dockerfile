FROM maven:3-eclipse-temurin-26@sha256:69a6c37f0a91fca2c9d0cdd684c9c0ea7b1bc00d4196749c24440b2e02ad163d AS build
WORKDIR /workspace
COPY backend-saas/pom.xml backend-saas/mvnw backend-saas/mvnw.cmd ./backend-saas/
COPY backend-saas/.mvn ./backend-saas/.mvn
RUN mvn -f backend-saas/pom.xml -B -q dependency:go-offline
COPY backend-saas/src ./backend-saas/src
RUN mvn -f backend-saas/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
WORKDIR /app
ARG UBUNTU_APT_MIRROR=""
COPY .github/scripts/configure-ubuntu-apt.sh /tmp/configure-ubuntu-apt.sh
RUN sh /tmp/configure-ubuntu-apt.sh "$UBUNTU_APT_MIRROR" \
    && rm /tmp/configure-ubuntu-apt.sh \
    && apt-get -o APT::Update::Error-Mode=any -o Acquire::Retries=3 update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/tpv-saas/backup-vault \
    && chown -R 10001:10001 /var/lib/tpv-saas \
    && chmod 700 /var/lib/tpv-saas/backup-vault
COPY --from=build /workspace/backend-saas/target/tpv-erp-saas-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
