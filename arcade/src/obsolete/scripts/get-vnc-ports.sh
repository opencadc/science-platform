#!/bin/bash
docker inspect --format "{{ index .Config.Labels \"canfar-net-vncPort\"}}" $(docker ps -q)
