FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /workspace/target/GeminiEligibilityCheck-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir /data && chown app:app /data
VOLUME /data
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
