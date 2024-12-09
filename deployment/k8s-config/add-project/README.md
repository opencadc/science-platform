# add-project script

To add a new project that shows up under the `projects` folder in Cavern.

## Obtain UID/GID

Projects are simply POSIX folders under the base project folder (see [`./config/projectdir`](./config/projectdir)).  As such, they need the owner's unique user id (UID) and a unique group ID (GID).  These are avaiable from the POSIX Mapper.

### CANFAR (AC)

Use a certificate or cookie to authenticate with AC:

```sh
curl -SsL -o cadccert.pem --netrc-file ~/.netrc "https://ws.cadc-ccda.hia-iha.nrc-cnrc.gc.ca/cred/generate?daysValid=30"

curl -E cadccert.pem "https://ws-cadc.canfar.net/ac/uidmap?user=<username-to-find>"

# Results in standard POSIX output:
<username-to-find>:x:uid:uid::

curl -E cadccert.pem "https://ws-cadc.canfar.net/ac/gidmap?group=<group-uri-to-find>"
# Example Group URI - ivo://cadc.nrc.ca/gms?mygroupname
# Results in standard POSIX output:
mygroupname:x:gid:
```

### SRCNet (OpenID Connect)

Use an access token to authenticate with the POSIX Mapper.

```sh
eval $(oidc-agent-service use) > /dev/null

# token-context-name is how the token was registered.
# See https://confluence.skatelescope.org/pages/viewpage.action?spaceKey=SRCSC&title=RED-10+Using+oidc-agent+to+authenticate+to+OpenCADC+services
export TOKEN=$(oidc-token token-context-name)

curl --header "authorization: bearer ${TOKEN}" "https://src.canfar.net/posix-mapper/uid?user=<username-to-find>"
# Results in standard POSIX output:
<username-to-find>:x:uid:uid::

curl --header "authorization: bearer ${TOKEN}" "https://src.canfar.net/posix-mapper/uid?group=<group-uri-to-find>"
# Example Group URI - ivo://canfar.net/gms?mygroupname
# Results in standard POSIX output:
mygroupname:x:gid:

```

Then update the appropriate Kubernetes Job file (`skaha-add-project-keel-[dev|prod].yaml`), then run it with `kubectl -n skaha-system apply -f <job-file.yaml>`.

Don't forget to clean up afterward:
```sh
kubectl -n skaha-system delete job skaha-add-project
```