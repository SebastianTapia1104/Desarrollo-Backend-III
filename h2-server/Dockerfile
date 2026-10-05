FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/h2-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
