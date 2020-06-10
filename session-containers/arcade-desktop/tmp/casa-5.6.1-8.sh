#!/bin/bash 

IP_ADDRESS=`hostname --ip-address`

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${arcade_username}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=canfar-registry.cloud.computecanada.ca/arcade-casa:5.6.1-8.el7" https://${arcade_hostname}/arcade/session/${VNC_PW}/app
