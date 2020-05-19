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
