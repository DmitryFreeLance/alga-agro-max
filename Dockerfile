FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY certs ./certs
RUN mvn -q -DskipTests clean package

RUN cp "${JAVA_HOME}/lib/security/cacerts" /tmp/cacerts \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-root-ca \
        -file /app/certs/russian-trusted-root-ca.pem \
        -keystore /tmp/cacerts -storepass changeit \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-sub-ca \
        -file /app/certs/russian-trusted-sub-ca.pem \
        -keystore /tmp/cacerts -storepass changeit

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Europe/Moscow
ENV APP_DB_PATH=/data/alga-agro.db

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates python3 python3-pip poppler-utils tesseract-ocr tesseract-ocr-rus \
    && python3 -m pip install --break-system-packages --no-cache-dir pdfplumber \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data /app/scripts

COPY --from=build /app/target/alga-agro-max-1.0.0.jar /app/app.jar
COPY scripts /app/scripts
COPY certs /usr/local/share/ca-certificates/alga-agro
COPY --from=build /tmp/cacerts /opt/java/openjdk/lib/security/cacerts

RUN update-ca-certificates

EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]
