# ============================================================
# STAGE 1 — BUILD
# Usa uma imagem com JDK completo só para compilar o projeto
# ============================================================
FROM eclipse-temurin:21-jdk AS builder

# Define o diretório de trabalho dentro do container
WORKDIR /app

# Copia os arquivos do Gradle primeiro (aproveitando cache de camadas)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
# Dá permissão de execução ao wrapper do Gradle
RUN chmod +x gradlew

# Baixa as dependências (camada separada = cache mais eficiente)
RUN ./gradlew dependencies --no-daemon

# Copia o restante do código-fonte
COPY src src

# Compila e gera o JAR, pulando os testes
RUN ./gradlew bootJar --no-daemon -x test

# ============================================================
# STAGE 2 — RUNTIME
# Usa uma imagem menor, só com JRE (sem ferramentas de build)
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copia apenas o JAR gerado no stage anterior
COPY --from=builder /app/build/libs/*.jar app.jar

# Porta que o Spring Boot vai expor
EXPOSE 8080

# Comando que inicia a aplicação ativando o profile "docker"
# Isso faz o Spring carregar o application-docker.properties
ENTRYPOINT ["java", "-jar", "app.jar"]


