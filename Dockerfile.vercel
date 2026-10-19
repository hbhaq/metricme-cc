FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY contracts contracts
COPY domain domain
COPY backend backend
RUN chmod +x gradlew && ./gradlew -PbackendOnly :backend:installDist --no-daemon --max-workers=2
FROM eclipse-temurin:17-jre
RUN groupadd -r metricme && useradd -r -g metricme metricme
WORKDIR /app
COPY --from=build --chown=metricme:metricme /src/backend/build/install/backend /app
USER metricme
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/backend"]
