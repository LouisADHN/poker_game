# syntax=docker/dockerfile:1

# ==================================================================
# Image du jeu de poker, construite en trois étapes.
# Seule la dernière étape est conservée dans l'image finale.
#
#   docker build -t poker-game .
# ==================================================================


# ------------------------------------------------------------------
# Étape 1 : compiler le front Vue
# ------------------------------------------------------------------
FROM node:22-alpine AS front
WORKDIR /app/poker-web

# Les dépendances d'abord : cette couche est réutilisée tant que package*.json ne change pas
COPY poker-web/package.json poker-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# Puis le code source, et la compilation.
# vite.config.ts écrit le résultat dans ../poker-server/src/main/resources/static
COPY poker-web/ ./
RUN npm run build


# ------------------------------------------------------------------
# Étape 2 : construire le serveur Spring Boot (avec le front compilé)
# ------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Le Maven Wrapper et les pom.xml
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY poker-engine/pom.xml poker-engine/
COPY poker-server/pom.xml poker-server/

# mvnw peut avoir des fins de ligne Windows (CRLF), illisibles par Linux : on les convertit
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Le code source des deux modules, puis le front compilé à l'étape 1
COPY poker-engine/src/ poker-engine/src/
COPY poker-server/src/ poker-server/src/
COPY --from=front /app/poker-server/src/main/resources/static/ poker-server/src/main/resources/static/

# Construction du JAR. Les tests sont lancés par le pipeline GitLab, avant cette étape.
# Le cache Maven (~/.m2) est conservé entre deux constructions : les dépendances
# ne sont téléchargées qu'une fois.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp package -DskipTests \
    && cp poker-server/target/poker-server-*.jar app.jar


# ------------------------------------------------------------------
# Étape 3 : l'image finale, avec uniquement Java et l'application
# ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Sécurité : l'application ne tourne pas en administrateur (root)
RUN addgroup -S poker && adduser -S poker -G poker
USER poker

WORKDIR /app
COPY --from=build /app/app.jar app.jar

EXPOSE 8080

# Docker vérifie régulièrement que l'application répond
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/ping || exit 1

# MaxRAMPercentage : Java utilise au plus 75 % de la mémoire allouée au conteneur
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]