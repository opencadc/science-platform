#!/bin/bash 

IP_ADDRESS=`hostname --ip-address`

/opt/shibboleth/bin/curl -v -L -k -E ${HOME}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=nat1405/nifty:0.1" https://${skaha_hostname}/arcade/skaha/${VNC_PW}/app
