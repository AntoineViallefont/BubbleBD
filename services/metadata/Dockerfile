FROM python:3.12-slim
WORKDIR /app
COPY server.py mistral_provider.py test_server.py test_mistral.py README.md LICENSE Dockerfile ./
USER 65534:65534
ENV PORT=8080
EXPOSE 8080
CMD ["python", "server.py"]
