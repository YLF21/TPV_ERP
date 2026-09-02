FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /workspace
COPY backend-saas/pom.xml backend-saas/mvnw backend-saas/mvnw.cmd ./backend-saas/
COPY backend-saas/.mvn ./backend-saas/.mvn
RUN mvn -f backend-saas/pom.xml -B -q dependency:go-offline
COPY backend-saas/src ./backend-saas/src
RUN mvn -f backend-saas/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend-saas/target/tpv-erp-saas-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
