# software-containers
The modules in this directory can be run in the skaha desktop session environment.

## Container Requirements
Containers must be based on a standard Linux distribution.

### SSSD
Containers must have `sssd-client` and `acl` installed.

The file /etc/nsswitch.conf must include the sss module in the passwd, shadow, and group entries.  For example:

```
passwd:     sss files
shadow:     files sss
group:      sss files
```

### xterm
`xterm` must be installed on the container

## Initialization and Startup
The CMD and EXECUTABLE directives in a software container Dockerfile will be ignored on startup.  Instead, bash within an xterm will run.  CMD and EXECUTABLE are still useful for testing containers outside of skaha.

The container will be initially started by root but then switched to be run as the active CADC skaha user.

If the container needs to do any runtime initialization, that can be done in a script named `init.sh` in the `/skaha` root directory.  This script **must not block** and needs to return control to the calling process.

If `/skaha/init.sh` is provided, a sensible directive for testing the container via docker is `CMD ["/skaha/init.sh"]`
