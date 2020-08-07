# ARCADE: ALMA Data Reduction in the CANFAR Environment

# Direct file transfer setup tutorial
These steps will get you setup to transfer from your local machine directly to ARCADE.

1. Install vos tools on your local machine for the transfer (etc.) commands
    - Done per-user since it installs Python modules into your area
    - This command will create a `src` directory where it is run. You need to keep this directory around so make sure you to run the command where you do not mind having that directory stored.
`pip install -e 'git+https://github.com/andamian/vostools@s2737#egg=vostools&subdirectory=vos'`
1. Create the directory `~/.config/vos/` on your local machine.
1. Add a file to that directory called `vos-config` with the following contents.
    ```
    [vos]
    # List of VOSpace services known to vos, one entry per line:
    # resourceID = <resourceID> [<prefix>(default vos)]
    # prefix is used to identify files and directories on that service with
    # the command line. e.g. vos:path/to/file is the path to a file on the VOSpace
    # service with the vos prefix. Prefixes in the config file must be unique.
    
    resourceID = 
        ivo://cadc.nrc.ca/vault vos
        ivo://cadc.nrc.ca/arbutus-cavern arc
    ```
1. Create a temporary local certificate to authenticate you on the server with the following command.
    - It will prompt you for a password which will be the same one as you use to login to the CADC website.
`cadc-get-cert -u <CADC_username>`
1. Test this has worked by creating a test directory in your home directory on ARCADE with the following command.
`vmkdir arc:home/<ARCADE_username>/start_test`
1. If it worked, celebrate by deleting that test directory with this command.
`vrmdir arc:home/<ARCADE_username>/start_test`

# How to view and/or generate the URL of your ARCADE or CARTA session

**These steps are taking place on a terminal on your local machine, not within the ARCADE environment**\
(WARNING: There has also been issues with institutional network proxies not allowing some curl commands. Try from a local machine if this occurs.)

## Install or update the _vos_ tools Python package

Many interactions with the ARCADE system require CANFAR's _vos tools_ software. Follow the [CANFAR documentation](https://www.canfar.net/en/docs/storage/)  to install the _vos_ Python module and command line client.

## View the URLs of your running ARCADE and CARTA sessions (start here if you have the _vos_ python package installed on your computer)

1. Get your security credentials (standard CADC certificate expires every 10 days) \
`cadc-get-cert -u [your_cadc_username]`\
(enter your password at the prompt)

2. List the access URLs of your current ARCADE and CARTA sessions\
`curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session`

3. Open the URL(s) in your local web browser

## Create your own ARCADE and CARTA sessions

If your any of your sessions close, a new session (and URL) can be easily created.

### ARCADE
1. Get your security credentials (standard CADC certificate expires every 10 days) \
`cadc-get-cert -u [your_cadc_username]`\
(enter your password at the prompt)

2. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session -d "name=myname" -d “type=desktop"`

where `myname` is a name that you set for the session (e.g., `[your_cadc_username]-arcade`). This can be any string that you want, so long as it is a single string with no spaces.

### CARTA
To instead initiate a new CARTA session, the last part of the command would instead be “type=carta”.

2. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session -d "name=myname" -d “type=carta"`

You can then find the new session’s URL by running the same command as listed in Step 3 above:

3. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session`

## clipboard tutorial
You can view the tutorial on the arcade clipboard [here](Clipboard_Tutorial.pdf).

