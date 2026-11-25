# Package the distribution produced by ./gradlew -p lottery-worker installDist.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/install/legion-lottery-worker/ ./
RUN chmod +x bin/legion-lottery-worker
USER 10001:10001
ENTRYPOINT ["/app/bin/legion-lottery-worker", "serve"]
