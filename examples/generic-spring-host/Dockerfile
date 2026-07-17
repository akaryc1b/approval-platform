FROM eclipse-temurin:21-jre

WORKDIR /opt/approval-host
COPY target/approval-generic-spring-host-example-*.jar app.jar

RUN useradd --system --uid 10001 --create-home approval-host
USER 10001

EXPOSE 18080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/approval-host/app.jar"]
