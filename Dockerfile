# The ops-agent image. Distroless Java 21, non-root, no shell, no package manager.
#
# Build the jar first (./mvnw -B -ntp package -DskipTests), then:
#   docker build -t ops-agent:local .
# No key and no token is copied in: OPENAI_API_KEY and OPSAGENT_WEBHOOK_TOKEN come from the environment only.

FROM eclipse-temurin:21-jre AS extract
WORKDIR /build
COPY target/ops-agent-0.1.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER nonroot:nonroot
EXPOSE 8095
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
