FROM maven:3-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /workspace
COPY backend-saas/pom.xml backend-saas/mvnw backend-saas/mvnw.cmd ./backend-saas/
COPY backend-saas/.mvn ./backend-saas/.mvn
RUN mvn -f backend-saas/pom.xml -B -q dependency:go-offline
COPY backend-saas/src ./backend-saas/src
RUN mvn -f backend-saas/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend-saas/target/tpv-erp-saas-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
