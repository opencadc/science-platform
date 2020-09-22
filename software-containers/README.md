# software-containers
The modules here are astronomy containers built to be run in arcade.

## Container Requirements

### SSSD
Containers must have `sssd-client` and `acl` installed so that arcade can run them as the end user.

The file /etc/nsswitch.conf must include the sss module in the passwd, shadow, and group entries.  For example:

```
passwd:     sss files
shadow:     files sss
group:      sss files
```

### xterm
`xterm` must be installed on the container

### ENTRYPOINT
Use ENTRYPOINT to specify an executable to be run, for example a startup script which can execute several tasks when the container starts up.

### CMD
Use CMD to specify a command to be executed at the end of the Dockerfile, "xterm".

### desktop
Each container is associated with a desktop file, for example terminal.desktop, which includes the path to the shell script, for example terminal.sh, which initializes and starts up the container.

### shell script
A shell script is created for each container. The shell script initializes and starts up the container. As a result, PostAction is invoked. PostAction uses a default parameter to start up an xterm. However the shell script can pass a parameter to PostAction, which replaces the default parameter. The following is an example script that passes a parameter to PostAction:
```
session-containers/arcade-desktop/software-scripts/arcade-feedback.sh:5:/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${arcade_username}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=canfar-registry.cloud.computecanada.ca/arcade-firefox:latest" --data-urlencode "param=https://github.com/opencadc/arcade/issues" https://${arcade_hostname}/arcade/session/${VNC_PW}/app
```
