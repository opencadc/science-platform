#!/bin/bash 

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${skaha_username}/.ssl/cadcproxy.pem -X DELETE https://${skaha_hostname}/skaha/session/${VNC_PW}
