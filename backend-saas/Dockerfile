FROM maven:3-eclipse-temurin-26@sha256:c30718f442eb39c55b2d45440bb849d8755ad8d9d6ef67aadefcce150267116f AS build
WORKDIR /workspace
COPY backend-saas/pom.xml backend-saas/mvnw backend-saas/mvnw.cmd ./backend-saas/
COPY backend-saas/.mvn ./backend-saas/.mvn
RUN mvn -f backend-saas/pom.xml -B -q dependency:go-offline
COPY backend-saas/src ./backend-saas/src
RUN mvn -f backend-saas/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend-saas/target/tpv-erp-saas-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
