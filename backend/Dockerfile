FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app/oci-worker
COPY --from=builder /build/target/oci-worker-*.jar app.jar

RUN mkdir -p keys

EXPOSE 8818

ENTRYPOINT ["java", "-jar", "app.jar", \
  "--spring.config.additional-location=file:./application.yml"]
