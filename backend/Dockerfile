# syntax=docker/dockerfile:1

# Build: full JDK for the Maven wrapper.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -DskipTests package

# Runtime: JRE only. Render sets PORT; the app honours it via application.yml.
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=build --chown=appuser:appuser /app/target/backend-*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
