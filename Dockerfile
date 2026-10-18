FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring
COPY --from=build --chown=spring:spring /app/target/order_service-*.jar app.jar
COPY --chown=spring:spring docker-entrypoint.sh /app/docker-entrypoint.sh
USER spring
EXPOSE 8086
ENTRYPOINT ["/app/docker-entrypoint.sh"]
