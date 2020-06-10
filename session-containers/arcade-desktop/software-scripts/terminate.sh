#!/bin/bash 

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${arcade_username}/.ssl/cadcproxy.pem -X DELETE https://${arcade_hostname}/arcade/session/${VNC_PW}
