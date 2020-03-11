# The arcade REST API in a tomcat container instance

## building

```
gradle clean build
docker build -t arcade-tomcat:latest -f Dockerfile .
```

## checking it
```
docker run -it --rm arcade-tomcat:latest /bin/bash
```

## running it
```
docker run -d --rm arcade-tomcat:latest

## configuration

TBD
