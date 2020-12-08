#!/bin/bash 

IP_ADDRESS=`hostname --ip-address`

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${skaha_username}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=canfar-registry.cloud.computecanada.ca/arcade-firefox:latest" --data-urlencode "param=https://github.com/opencadc/arcade/issues" https://${skaha_hostname}/skaha/session/${VNC_PW}/app
