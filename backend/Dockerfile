FROM eclipse-temurin:21-jre-alpine

WORKDIR /app/oci-worker
COPY target/oci-worker-*.jar app.jar

RUN mkdir -p keys

EXPOSE 8818

ENTRYPOINT ["java", "-jar", "app.jar", \
  "--spring.config.additional-location=file:./application.yml"]
