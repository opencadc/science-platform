# ARCADE: ALMA Data Reduction in the CANFAR Environment

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

# How To Use SSHFS to Mount the ARCADE File System Over SSH and use `rsync`

In many cases it can become cumbersome to transfer large files to and from ARCADE. Luckily there is a way to mount the ARCADE file system to your local computer so you can make changes on the fly and treat the ARCADE file system as local storage. In this article, we will show you how to do exactly that.

## Installing SSHFS
### On Ubuntu/Debian

SSHFS is Linux based software that needs to be installed on your local computer. On Ubuntu and Debian based systems it can be installed through apt-get.

`sudo apt-get install sshfs`

### On Mac OSX

You can install SSHFS on Mac OSX (although it's often already there). You will need to download FUSE and SSHFS from the osxfuse site (https://osxfuse.github.io/)

## Mounting the Remote File System

The following instructions will work for both Ubuntu/Debian and OSX. Instructions for Windows systems can be found at the bottom of this page (https://www.digitalocean.com/community/tutorials/how-to-use-sshfs-to-mount-remote-file-systems-over-ssh).

To start we will need to create a local directory in which to mount ARCADE's file system, cavern.

`mkdir /mnt/cavern`

Now we can use sshfs to mount the file system locally with the following command. If your VPS was created with a password login the following command will do the trick. You will be asked for your virtual server’s root password during this step.

`sshfs -p 64022 -o allow_other,defer_permissions [uname]@proto.canfar.net:/ $HOME/mnt/cavern`

## How To Use Rsync to Sync Local and ARCADE Directories over SSH

Rsync, which stands for “remote sync”, is a remote and local file synchronization tool. It uses an algorithm that minimizes the amount of data copied by only moving the portions of files that have changed. Further Rsync examples and docs are [here](https://www.digitalocean.com/community/tutorials/how-to-use-rsync-to-sync-local-and-remote-directories-on-a-vps).

Do an sshfs mount on your local machine:
`sshfs -p 64022 -o allow_other,defer_permissions [uname]@proto.canfar.net:/ $HOME/mnt/cavern`

Perform your rsync:
`rsync -rvP source_dir $HOME/mnt/cavern/destination_dir/`

# How to set up CADC's `vos` tools to transfer from your local computer to arcade

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


# [How to use the clipboard to copy/paste into and out of ARCADE](Clipboard_Tutorial.pdf)



