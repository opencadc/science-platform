# arcade

arcade is a Java servlet implementation offering a REST API to all arcade functionality.

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
