# Multi-stage build: a JDK is needed to compile, but not to run.
# Stage 1 alone is ~600MB with the full Maven+JDK toolchain; only the jar it
# produces crosses into stage 2.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so `docker build` reuses this layer until pom.xml changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# Stage 2: JRE only, no Maven, no JDK, no source.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root: the process gets no more filesystem access than it needs.
RUN addgroup -S resolveai && adduser -S resolveai -G resolveai
COPY --from=build /build/target/resolveai-*.jar app.jar
RUN chown resolveai:resolveai app.jar
USER resolveai

# Heroku assigns $PORT at runtime; server.port reads it with a local-dev fallback.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

# Explicit, absolute memory caps rather than -XX:MaxRAMPercentage: on this
# dyno the JVM's container-memory detection returned a figure far larger than
# the real 512MB quota, so a percentage of it still blew past the limit
# (observed: 1146MB RSS against a 512MB cap). Budgeted so heap + metaspace +
# code cache + direct buffers + ~50 thread stacks stays under 512MB with room
# to spare, since this is a demo dyno, not a production one.
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=32m", "-Xss512k", "-jar", "app.jar"]
