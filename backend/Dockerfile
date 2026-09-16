# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon --no-configuration-cache clean buildFatJar

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /src/build/libs/vrtic-backend-all.jar /app/app.jar
USER app
EXPOSE 8080
# Entry: `serve` (default) | `migrate` | `benchmark-argon2`
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
CMD ["serve"]
