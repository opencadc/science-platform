# arcade-wsproxy

This is an httpd container that proxies websocket connections to arcade-desktop

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
