# Science Platform

The Science Platform is designed to be a powerful and flexible environment for scientific research, and is built on the principles of open-source software, open standards, and open access to data.

As a part of the science platform, Skaha is a Java servlet implementation offering a REST API to the functionality of the platform.  It is designed to be run in a containerized environment, to manage the underlying orchestration platform (Kubernetes) and the lifecycle of the user's containers.

## Software Releases

## Official Docker Image

The official Docker image is via https://images.opencadc.org/platform/skaha container registry. The official container image with two flavors: a `tagged` and a `edge` version. The tagged version is the stable version of the software, corresponding to a [Github Release](https://github.com/opencadc/science-platform/releases), while the `edge` version is the latest build from the `main` branch. The newest tagged version is also available under the `latest` tag.

```bash
docker pull images.opencadc.org/platform/skaha:latest # latest tagged version
docker pull images.opencadc.org/platform/skaha:0.25.0 # specific tagged version
docker pull images.opencadc.org/platform/skaha:edge # edge version
```

### Release Versions

Skaha follows the semantic versioning scheme. The version number is in the format of `MAJOR.MINOR.PATCH`. The `MAJOR` version is incremented for incompatible API changes, the `MINOR` version is incremented for backward-compatible changes, and the `PATCH` version is incremented for backward-compatible bug fixes.

### Local Build

To build the software locally, you need to have docker installed on your machine. The following commands will build the software and create a docker image.

```
cd science-platform
docker buildx build -f skaha/Dockerfile -t skaha:local --platform=linux/amd64 .
```

## Running Skaha

To run skaha, you need to have a running Kubernetes cluster and a significant configuration, which is beyond the scope of this document, but covered under the Helm Chart Deployments provided by the OpenCADC team. See, [opencadc/deployments](https://github.com/opencadc/deployments) repository for more information. But, if you want to run skaha locally, you can use the following command.
```
docker run -it --rm images.opencadc.org/platform/skaha:latest /bin/bash
```

## Configuration

The following configuration files must be available in `/config` directory of the container:

### catalina.properties

skaha is run on the `images.opencadc.org/library/cadc-tomcat` container (https://github.com/opencadc/docker-base/tree/master/cadc-tomcat), so must be configured as per the documentation on that page.

### cadc-registry.properties

The `cadc-registry.properties` file contains the configuration for the registry service.  The following is an example configuration:

```
ivo://ivoa.net/std/GMS#groups-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/GMS#search-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#users-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#reqs-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#login-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#modpass-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#resetpass-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#whoami-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/CDP#delegate-1.0 = ivo://cadc.nrc.ca/cred
ivo://ivoa.net/std/CDP#proxy-1.0 = ivo://cadc.nrc.ca/cred
```

## Developer Notes

Skaha is written in Java and currently developed against Java 11.  The project uses Gradle for build automation.  To build the project, run the following command:

```
cd science-platform/skaha
./gradlew clean build
```

### Contributing
Contributions to the project are welcomed.  Please see the [CONTRIBUTING.md](../CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) files for more information.

### Code Style
Skaha uses `pre-commit` to enforce code style and formatting.  To install the pre-commit hooks, run the following command:

```
pip3 install --user pre-commit
pre-commit install --hook-type commit-msg
```

This will install the pre-commit hooks to run on every commit, even the checks defined under `./gradlew check`. If you want to run the hooks manually, you can run the following command:

```
pre-commit run --all-files
```

## Code Formatting

Skaha uses `spotless` to enforce Java Code Formatting standards.  To format the code, run the following command:

```bash
cd science-platform/skaha
./gradlew spotlessApply
```

You can find the spotless configuration in the `spotless` block in the `build.gradle` file.

```java
spotless {
  java {
    // Interpret all files as utf-8
    encoding 'UTF-8'
    // Only require formatting of files that diff from main
    ratchetFrom 'origin/main'
    // Use the default importOrder configuration
    importOrder()
    // Remove unused imports
    removeUnusedImports()
    // Google Java Format, Android Open Source Project style which uses 4 spaces for indentation
    palantirJavaFormat('2.50.0').formatJavadoc(true)
    // Format annotations on a single line
    formatAnnotations()
  }
}
check.dependsOn spotlessCheck
```

We specificly use the [Palantir Java Format](https://github.com/palantir/palantir-java-format), which is a a modern, lambda-friendly, 120 character Java formatter focused on readability and derived from Google Java Format.
