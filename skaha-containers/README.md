# skaha session and software containers

skaha supports two types of containers: session containers and software containers.

Session containers are HTML5/websocket applications that are made available through skaha on a browser.  Examples of session containers in skaha are the skaha desktop (NoVNC), CARTA, and Notebook containers.  The skaha desktop container is also known as the ARCADE software environment.  See the [ARCADE github project](https://github.com/canfar/arcade.git "ARCADE").

Software containers are launched and viewed through the skaha desktop container.  Examples of software containers in skaha are CASA, the multi-purpose terminal, and containers for Gemini processing.

Some of the recipes for building these containers are in this section of skaha git repository.  They can also be managed and hosted elsewhere.  The source for CASA containers are hosted in the [ARCADE](https://github.com/canfar/arcade.git "ARCADE") respository.  However, wherever the source is hosted, they must meet a minimal set of requirements and expectations for both session and software containers that run in skaha.

## Buliding skaha containers

Containers that are to run in skaha are required to meet rules documented in this section.

Containers must be based on a standard Linux distribution.

#### SSSD
Containers must have `sssd-client` and `acl` installed.

#### nsswitch.conf
The file /etc/nsswitch.conf must include the sss module in the passwd, shadow, and group entries.  For example:

```
passwd:     sss files
shadow:     files sss
group:      sss files
```
### Additional rules for software containers

#### xterm
For software containers only, `xterm` must be installed.

#### Initialization and Startup
The CMD and EXECUTABLE directives in a software container Dockerfile will be ignored on startup.  Instead, bash within an xterm will run.  CMD and EXECUTABLE are still useful for testing containers outside of skaha.

The container will be initially started by root but then switched to be run as the active CADC skaha user.

If the container needs to do any runtime initialization, that can be done in a script named `init.sh` in the `/skaha` root directory.  This script **must not block** and needs to return control to the calling process.

If `/skaha/init.sh` is provided, a sensible directive for testing the container via docker is `CMD ["/skaha/init.sh"]`

### Container process owners
Containers, in skaha, are always executed as the *CADC User* and never as root.  This applies to both session and sofware containers.  Operations that require root must be done at the build phase of the image.

## Publishing skaha containers

skaha runs a instance of the [Harbor](https://goharbor.io/) opensource project as a container registry.  All session and software containers launched from skaha come from this registry.  The registry and portal are hosted at http://harbor.canfar.net

One uses their CADC userid and password to login to the harbor portal via the `Login via OIDC Provider` button.  On first login, users will be asked to provide a harbor username.  This should be set to the same as their CADC account name.

Harbor hosts a number of *projects*.  Each project has a list of members that are able to publish to that project.  If you wish to be added to a specific project please contact support@canfar.net

Images hosted in harbor may be subject to image security scanning.

The session and software images that are able to be launched in skaha are limited to a set of projects hosted in harbor.

## Testing
Before publishing a new or modified image testing should be done to ensure it works as expected.  Some testing can be done by using `docker` to run the image.  For software containers, docker will not be able to provide a graphical display of CASA windows.  That testing must be done in ARCADE itself.

To test software images, users can push the image to the 'testing sandbox' project in the image registry (to be define).  The images in this registry will not be displayed in the desktop menu, but can still be launched using the API of skaha.  Users can iterate by making corrections to the image and overwritting the image in the sandbox.  Once testing is complete the image can be published to the correct project in Harbor and hence released into the skaha system.

Session containers should be mostly testable with only docker.
