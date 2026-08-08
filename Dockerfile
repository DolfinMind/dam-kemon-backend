FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew clean test bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --system damkemon && useradd --system --gid damkemon --home-dir /app damkemon
WORKDIR /app
COPY --from=build --chown=damkemon:damkemon /workspace/build/libs/*.jar app.jar
USER damkemon
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
