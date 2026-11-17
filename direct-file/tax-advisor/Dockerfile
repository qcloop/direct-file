# Build context: the parent direct-file directory, after mvnw package in tax-advisor.
FROM amazoncorretto:25-al2023-headless
WORKDIR /app
RUN yum --releasever=2023.12.20261112 update -y && yum install -y curl-minimal && yum clean all
ENV SPRING_PROFILES_ACTIVE=redis \
    DF_TAX_KNOWLEDGE_ROOT=/app/tax-knowledge \
    LOGGING_LEVEL_ROOT=INFO \
    LOGGING_LEVEL_COM_REALWORLD_TAX_ADVISOR=INFO \
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=WARN \
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
    MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,redis \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Duser.home=/tmp"
COPY --chown=10001:10001 tax-advisor/target/tax-advisor-0.0.1.jar /app/app.jar
COPY --chown=10001:10001 tax-knowledge/rules /app/tax-knowledge/rules
COPY --chown=10001:10001 tax-knowledge/questions /app/tax-knowledge/questions
COPY --chown=10001:10001 tax-knowledge/evidence /app/tax-knowledge/evidence
COPY --chown=10001:10001 tax-knowledge/conflicts /app/tax-knowledge/conflicts
COPY --chown=10001:10001 tax-knowledge/sources /app/tax-knowledge/sources
COPY --chown=10001:10001 tax-knowledge/releases /app/tax-knowledge/releases
USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD curl -fsS http://127.0.0.1:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
