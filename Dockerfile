FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src/ src/
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -g 1000 spring && adduser -D -u 1000 -G spring spring

COPY --from=build /app/build/libs/shortliner-gateway-*.jar app.jar

RUN chown spring:spring app.jar

USER 1000

EXPOSE 8084

# Netty still uses sun.misc.Unsafe and loads native libs; without these flags Java 25
# prints multi-line warnings to stderr, which end up as non-JSON lines in Loki.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
