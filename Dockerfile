FROM m.daocloud.io/docker.io/library/maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM m.daocloud.io/docker.io/library/eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends git openssh-client ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/blog-service-0.1.0.jar /app/app.jar
# 由 tini 回收 Git 等命令遗留的孤儿进程，并向 Java 转发终止信号。
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/app.jar"]
