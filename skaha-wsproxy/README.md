# skaha-wsproxy version 0.3

This is an httpd container that proxies websocket connections to skaha-desktop

## building

```
docker build -t skaha-wsproxy:0.3 -f Dockerfile .
```

## checking it
```
docker run -it --rm skaha-wsproxy:0.3 /bin/bash
```

## running it
```
docker run -d --rm skaha-wsproxy:0.3

## configuration

TBD
