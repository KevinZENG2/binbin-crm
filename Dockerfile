FROM node:24-bookworm-slim AS ui
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN VITE_RUNTIME_MODE=live npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS java
WORKDIR /build
COPY server/pom.xml ./pom.xml
COPY server/src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre
WORKDIR /opt/crm
RUN useradd --system --uid 10001 crm
COPY --from=java /build/target/crm-server-0.18.0.jar ./crm.jar
COPY --from=ui /build/dist ./ui
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/opt/crm/crm.jar"]
