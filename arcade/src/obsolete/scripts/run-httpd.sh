#!/bin/bash

# note: the mounting of the databench ui is temporary until a home is found for it
docker run --rm -d -it --net=arcade-net --name=arcade-httpd --hostname=arcade-httpd -p 80:80 -p 443:443 -v ${ARCADE_LOG_DIR}/httpd:/etc/httpd/logs -v /var/run/docker.sock:/var/run/docker.sock bucket.canfar.net/arcade-httpd:0.1.1
