# The arcade web socket proxy to desktop sessions

## building

```
gradle clean build
docker build -t arcade-wsproxy:latest -f Dockerfile .
```

## checking it
```
docker run -it --rm arcade-wsproxy:latest /bin/bash
```

## running it
```
docker run -d --rm arcade-wsproxy:latest

## configuration

TBD
