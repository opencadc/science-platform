# arcade-wsproxy version 0.1

This is an httpd container that proxies websocket connections to arcade-desktop

## building

```
docker build -t arcade-wsproxy:0.1 -f Dockerfile .
```

## checking it
```
docker run -it --rm arcade-wsproxy:0.1 /bin/bash
```

## running it
```
docker run -d --rm arcade-wsproxy:0.1

## configuration

TBD
