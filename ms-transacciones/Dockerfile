FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/ms-transacciones-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
EXPOSE 61616
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
