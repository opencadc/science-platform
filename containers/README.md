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

`Session containers` are HTML5/websocket applications that are made available through skaha on a browser.  Examples of session containers in skaha are the skaha desktop (NoVNC), CARTA, and Notebook containers.  The skaha desktop container is also known as the ARCADE software environment.

`Software containers` are launched and viewed through the skaha desktop container.  Examples of software containers in skaha are CASA, the multi-purpose terminal, TOPCAT, and containers for Gemini processing.

Some of the recipes (Dockerfiles) for building these containers are in this directory of skaha git.  They can also be managed and hosted elsewhere.  The source for CASA containers are hosted here [ARCADE-CASA](software-containers/arcade-casa "ARCADE-CASA").  However, wherever the source is hosted, containers must meet a minimal set of requirements and expectations for execution in skaha.

<a name="building"></a>
## Building skaha containers

Containers that are to run in skaha are required to meet rules documented in this section.

Containers must be based on a standard Linux distribution.

#### SSSD
For linux group id (gid) names to be resolved, the container must have `sssd-client` and `acl` installed, and must provide an nsswitch.conf file as described below.  If any of these are missing, only gids will be displayed (when `id` is typed for example), but file system authorization will continue to work as expected.

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
1. `notebook` - [skaha-notebook](session-containers/skaha-notebook) - For Jupyter Notebook servers, startup uses the standard `start-notebook.sh` script.
1. `carta` - [skaka-carta](session-containers/skaha-carta) - Initialization and startup is done through a customized script, `skaha-carta`.
1. `desktop` - [skaha-desktop](session-containers/skaha-desktop) - Desktop session startup is managed by the skaha infrastructure.

There may be multiple versions of the same type of session container, but the startup procedure for these must remain the same for them to be of the same type.

#### software container initialization

The CMD and EXECUTABLE directives in a software container Dockerfile will be ignored on startup.  Instead, bash within an xterm will run.  CMD and EXECUTABLE are still useful for testing containers outside of skaha.

If the container needs to do any runtime initialization, that can be done in a script named `init.sh` in the `/skaha` root directory.  This script **must not block** and needs to return control to the calling process.

If `/skaha/init.sh` is provided, a sensible directive for testing the container via docker is `CMD ["/skaha/init.sh"]`

Another option is for containers to make available a file named `/skaha/startup.sh`.  If it exists, it will be called with a single parameter, which is the command `startup.sh` must run in order to execute on the platform.  So, the end of `startup.sh` should do: `exec "$@"` to execute the incoming parameter.  Containers should use startup.sh when environment must be made available to the context of the application.

<a name="publishing"></a>
## Publishing skaha containers

### Step 1: Create a harbor account
skaha runs a instance of the [Harbor](https://goharbor.io/) opensource project as a container registry.  Session and software containers launched can be launched from this registry.

If you have logged into harbor before then step 1 can be skipped.

1. Go to https://images.canfar.net
2. Press the `Login with OIDC Provider` button.
3. Enter your CADC username and password.
4. When prompter for a harbor userid, use your CADC userid.

After these steps you now have a harbor account and can see the projects through this portal.  If you wish to publish to any of the projects, contact the project admistrator (or contact support@canfar.net) and ask for 'Development' access to the project.

### Step 2: Docker login to harbor
1. From the harbor portal, go to the top right, click on your username, then go to 'User Profile'.
2. Set your CLI secret -- this is the password to use for `docker login` commands.  You can copy the existing, generated secret, or 'upload' (enter) your own.
3. From the computer on which you have built the docker image you wish to publish, do a docker login:

```docker login images.canfar.net```

Your userid is your CADC userid, and your password the value of the CLI Secret mentioned above.

### Step 3: Push your image to your project
1. On the same computer, find the `IMAGE ID` of the image you'd like to push with the command

```docker images```

2. Tag the image for harbor:

```docker tag <IMAGE ID> images.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>``` 

where:
   * `<PROJECT>` is the project to which you've been granted Developer access.
   * `<MY IMAGE NAME>` is the name of the image you are publishing.
   * `<IMAGE VERSION>` is the version of the image.
   
3. Push the image to harbor, with:

```docker push images.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>```

### Step 4: Label your image type

1. Go back to https://images.canfar.net
2. Click on your project, then on your newly pushed image (also called a repository in harbor).
3. Select the 'artifact' with the correct version (tag).
4. Under the 'Actions' drop-down, apply the approripate label to the artifact.

## Science Platform Portal

A number of the steps below can be done using the CANFAR Science Platform Portal at https://www.canfar.net

## Listing images in skaha

Once publishing and labeling has been completed, the image will be visible to skaha.  It can then be seen on the Science Platform Portal, or with the folowing command:

```curl -E <cadcproxy.pem> https://ws-uv.canfar.net/skaha/image```

## Listing resource contexts

The available cores and RAM in skaha can be seein from the Science Platform Portal, or viewed with:

```curl -E <cadcproxy.pem> https://ws-uv.canfar.net/skaha/context```

<a name="launching"></a>
## Launching containers in skaha

### Session containers

1. Use the Science Platform Portal or this curl command to launch your newly published image:

```curl -E <cadcproxy.pem> https://ws-uv.canfar.net/skaha/session -d "name=<arbitrary-name>" -d "image=images.canfar.net/<PROJECT>/<MY IMAGE NAME>:<IMAGE VERSION>"```

If non-default values for cores and/or ram is preferred, the parameters `-d cores=<cores>` and `-d ram=<ram>` can be added to the session launching command above.

2. Use the Science Platform Portal or this curl command to find the URL to your session:

```curl -E <cadcproxy.pem> https://ws-uv.canfar.net/skaha/session```

If this is the first time this image has been launched in may take a few minutes for the cloud do retrieve the image from harbor.  If this is a `notebook` image you can see the JupyterLab view by changing the end of the URL from `tree` to `lab`.

### Software containers (for desktop sessions)

Once a software container has been pushed to harbor, it must be labelled with `desktop-app`.

To then make it appear in the Applications->Astro Software menu on the desktop a new desktop session must be started.

The desktop menu items in Applications->Astro Software are organized by harbor project.  A sub-folder is created for each project.  Then, each version of the artifacts (images) within that project will be displayed in the project sub-folder.  For example, the desktop-app image identified by URI:

```images.canfar.net/skaha/terminal:1.0```

will be placed in the desktop menu like so:

```Applications -> Astro Software -> skaha -> terminal:1.0```

<a name="testing"></a>
## Testing
Before publishing a new or modified image, testing should be done to ensure it works as expected.

For session containers, nearly all testing can be done by using `docker` to run the image.  A port should be exposed so you can connect your browser to the locally running session.

For software containers, docker will not be able to provide a graphical display of CASA windows, so most testing must be done in a skaha-desktop instance running in the cloud.
