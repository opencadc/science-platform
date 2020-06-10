#!/bin/bash 

IP_ADDRESS=`hostname --ip-address`

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${arcade_username}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=canfar-registry.cloud.computecanada.ca/arcade-casa:4.7.0-el6" https://${arcade_hostname}/arcade/session/${VNC_PW}/app
