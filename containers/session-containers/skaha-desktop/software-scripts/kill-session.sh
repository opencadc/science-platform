#!/bin/bash 

/opt/shibboleth/bin/curl -v -L -k -E ${HOME}/.ssl/cadcproxy.pem -X DELETE https://${skaha_hostname}/skaha/session/${VNC_PW}
