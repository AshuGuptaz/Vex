# Vex server container.
# Prefer the Jib build: `mvn -pl server -am package jib:dockerBuild`.
# This Dockerfile is the manual fallback for environments without Jib.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY pom.xml .
COPY core/pom.xml core/pom.xml
COPY storage/pom.xml storage/pom.xml
COPY server/pom.xml server/pom.xml
COPY bench/pom.xml bench/pom.xml
RUN apt-get update \
 && apt-get install -y --no-install-recommends maven \
 && rm -rf /var/lib/apt/lists/*
COPY core/src core/src
COPY storage/src storage/src
COPY server/src server/src
RUN mvn -pl server -am -DskipTests package

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/server/target/vex-server-*.jar /app/vex-server.jar
ENV VEX_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/vex-server.jar"]
