FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Europe/Moscow
ENV APP_DB_PATH=/data/alga-agro.db

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip poppler-utils tesseract-ocr tesseract-ocr-rus \
    && python3 -m pip install --break-system-packages --no-cache-dir pdfplumber \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data /app/scripts

COPY --from=build /app/target/alga-agro-max-1.0.0.jar /app/app.jar
COPY scripts /app/scripts

EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]
