FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf

COPY app /app

ENTRYPOINT ["java", "-cp", "/app/test-classes:/app/classes:/app/lib/*"]
