# skaha session and software containers

1. [Introduction](#intro)
1. [Building](#building)
1. [Initialization](#init)
1. [Publishing](#publishing)
1. [Launching](#launching)
1. [Testing](#testing)

<a name="intro"></a>
## Introduction

skaha supports two types of containers: `session containers` and `software containers`.

`Session containers` are HTML5/websocket applications that are made available through skaha on a browser.  Examples of session containers in skaha are the skaha desktop (NoVNC), CARTA, and Notebook containers.  The skaha desktop container is also known as the ARCADE software environment.  See the [ARCADE github project](https://github.com/canfar/arcade.git "ARCADE").

`Software containers` are launched and viewed through the skaha desktop container.  Examples of software containers in skaha are CASA, the multi-purpose terminal, and containers for Gemini processing.

Some of the recipes for building these containers are in this section of skaha git repository.  They can also be managed and hosted elsewhere.  The source for CASA containers are hosted in the [ARCADE](https://github.com/canfar/arcade.git "ARCADE") respository.  However, wherever the source is hosted, they must meet a minimal set of requirements and expectations for both session and software containers that run in skaha.

<a name="building"></a>
## Building skaha containers

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

<a name="init"></a>
## Initialization and Startup

#### Container process owners
Containers, in skaha, are always executed as the *CADC User* and never as root.  This applies to both session and sofware containers.  Operations that require root must be done at the build phase of the image.  If runtime root access is required, it can be done by giving sudo access to specific actions.

#### session container initialization
Initialization for session containers is based on the session container *type*.  There are currently three types with different startup procedures:
1. `desktop` - [skaha-desktop](session-containers/skaha-desktop) - Initialization and startup is performed by the command specified in the Dockerfiles.
1. `carta` - [skaka-carta](session-containers/skaha-carta) - Initialization and startup is done through a customized script, `skaha-carta`.
1. `notebook` - [skaha-notebook](session-containers/skaha-notebook) - For Jupyter Notebook servers, startup uses the standard `start-notebook.sh` script.

There may be multiple versions of the same type of session container, but the startup procedure for these must remain the same for them to be of the same type.

#### software container initialization

The CMD and EXECUTABLE directives in a software container Dockerfile will be ignored on startup.  Instead, bash within an xterm will run.  CMD and EXECUTABLE are still useful for testing containers outside of skaha.

If the container needs to do any runtime initialization, that can be done in a script named `init.sh` in the `/skaha` root directory.  This script **must not block** and needs to return control to the calling process.

If `/skaha/init.sh` is provided, a sensible directive for testing the container via docker is `CMD ["/skaha/init.sh"]`

<a name="publishing"></a>
## Publishing skaha containers

### Step 1: Create a harbor account
skaha runs a instance of the [Harbor](https://goharbor.io/) opensource project as a container registry.  Session and software containers launched can be launched from this registry.

If you have logged into harbor before then step 1 can be skipped.

1. Go to https://harbor.canfar.net
2. Press the `Login with OIDC Provider` button.
3. Enter your CADC username and password.
4. When prompter for a harbor userid, use your CADC userid.

After these steps you now have a harbor account and can see the projects through this portal.  If you wish to publish to any of the projects, contact the project admistrator (or contact support@canfar.net) and ask for 'Development' access to the project.

### Step 2: Docker login to harbor
1. From the harbor portal, go to the top right, click on your username, then go to 'User Profile'.
2. Set your CLI secret -- this is the password to use for `docker login` commands.  You can copy the existing, generated secret, or 'upload' (enter) your own.
3. From the computer on which you have built the docker image you wish to publish, do a docker login:

```docker login harbor.canfar.net```

Your userid is your CADC userid, and your password the value of the CLI Secret mentioned above.

### Step 3: Push your image to your project
1. On the same computer, find the `IMAGE ID` of the image you'd like to push with the command

```docker images```

2. Tag the image for harbor:

```docker tag <IMAGE ID> harbor.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>``` 

where:
   * `<PROJECT>` is the project to which you've been granted Developer access.
   * `<MY IMAGE NAME>` is the name of the image you are publishing.
   * `<IMAGE VERSION>` is the version of the image.
   
3. Push the image to harbor, with:

```docker push harbor.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>```

<a name="launching"></a>
## Launching containers in skaha

### Session containers

1. Use the CANFAR Science Platform Portal (TBD) or this curl command to launch your newly published image:

```curl -E <cadcproxy.pem> https://rc-uv.canfar.net/skaha/session -d "name=<arbitrary-name>" -d "image=harbor.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>"```

2. Use the CANFAR Science Platform Portal (TBD) or this curl command to find the URL to your session:

```curl -E <cadcproxy.pem> https://rc-uv.canfar.net/skaha/session```

If this is the first time this image has been launched in may take a few minutes for the cloud do retrieve the image from harbor.  If this is a `notebook` image you can see the JupyterLab view by changing the end of the URL from `tree` to `lab`.

### Software containers

(To be completed)

<a name="testing"></a>
## Testing
Before publishing a new or modified image, testing should be done to ensure it works as expected.

For session containers, nearly all testing can be done by using `docker` to run the image.  A port should be exposed so you can connect your browser to the locally running session.

For software containers, docker will not be able to provide a graphical display of CASA windows, so most testing must be done in a skaha-desktop instance running in the cloud.
