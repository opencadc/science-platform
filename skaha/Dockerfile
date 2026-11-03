# To build the image, run the following command:
#   git clone https://github.com/opencadc/science-platform.git
#   cd science-platform && docker build -t skaha:local -f skaha/Dockerfile .
# Note: The build requires the .git directory to be included in the build context.
FROM eclipse-temurin:21-alpine AS base

FROM base AS builder
COPY . /src
WORKDIR /src/skaha
# CI runs the tests; the image build only compiles and packages.
RUN ./gradlew clean spotlessCheck build -x test --no-daemon

FROM ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.33.1-2@sha256:6886169e41415eedcaa04d67485592022026eab5b33e2c83ce86561a72f15452 AS otel-javaagent

FROM images.opencadc.org/library/cadc-tomcat:1 AS production

COPY --from=otel-javaagent /javaagent.jar /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar
COPY --from=builder /src/skaha/build/libs/skaha.war /usr/share/tomcat/webapps/
