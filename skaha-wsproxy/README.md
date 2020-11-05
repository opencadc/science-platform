# skaha-wsproxy version 0.1

This is an httpd container that proxies websocket connections to skaha-desktop

## building

```
docker build -t skaha-wsproxy:0.1 -f Dockerfile .
```

## checking it
```
docker run -it --rm skaha-wsproxy:0.1 /bin/bash
```

## running it
```
docker run -d --rm skaha-wsproxy:0.1

## configuration

TBD
