# ARCADE Tutorials

#### Table of Contents
- [How to start an ARCADE or CARTA session](#how-to-start-an-arcade-or-carta-session)
  * [Interacting with sessions using the command line](#interacting-with-sessions-using-the-command-line)
    + [Install or update the _vos tools_ Python package](#install-or-update-the--vos-tools--python-package)
  * [How to list the URLs of your running ARCADE and CARTA sessions (start here if you have the _vos_ python package installed on your computer)](#how-to-list-the-urls-of-your-running-arcade-and-carta-sessions--start-here-if-you-have-the--vos--python-package-installed-on-your-computer-)
  * [Create your own ARCADE and CARTA sessions](#create-your-own-arcade-and-carta-sessions)
    + [ARCADE](#arcade)
    + [CARTA](#carta)
  * [How to terminate your session using `curl`](#how-to-terminate-your-session-using--curl-)
- [Transfering data into and out of ARCADE](#transfering-data-into-and-out-of-arcade)
  * [How To Use SSHFS to Mount the ARCADE File System Over SSH and Use `rsync`](#how-to-use-sshfs-to-mount-the-arcade-file-system-over-ssh-and-use--rsync-)
    + [Installing SSHFS](#installing-sshfs)
      - [On Ubuntu/Debian](#on-ubuntu-debian)
      - [On Mac OSX](#on-mac-osx)
    + [Mounting the Remote File System](#mounting-the-remote-file-system)
      - [On Ubuntu/Debian](#on-ubuntu-debian-1)
      - [On Mac OSX](#on-mac-osx-1)
    + [Syncing Local and ARCADE Directories with `rsync` Over SSH](#syncing-local-and-arcade-directories-with--rsync--over-ssh)
  * [How to set up CADC's _vos tools_ to transfer from your local computer to arcade](#how-to-set-up-cadc-s--vos--tools-to-transfer-from-your-local-computer-to-arcade)
  * [How to verify data transferred to ARCADE](#how-to-verify-data-transferred-to-arcade)
- [How to manage Python environments with conda on ARCADE](#how-to-manage-python-environments-with-conda-on-arcade)
- [How to use the clipboard to copy/paste into and out of ARCADE](#how-to-use-the-clipboard-to-copy-paste-into-and-out-of-arcade)

# How to start an ARCADE or CARTA session

ARCADE and CARTA sessions are launched from [demo.canfar.net](https://demo.canfar.net/en/) (login required). 

## Interacting with sessions using the command line

In some cases (e.g., troubleshooting), it is desirable to generate, view, or terminate your ARCADE and CARTA session URLS using the command line on your local machine.

WARNING: There has also been issues with institutional network proxies not allowing some curl commands. Try from a local machine if this occurs.

### Install or update the _vos tools_ Python package

Interacting with the ARCADE system using the command line requires CANFAR's _vos tools_ python package. Follow the [CANFAR documentation](https://www.canfar.net/en/docs/storage/)  to install the _vos_ Python module and command line client.

## How to list the URLs of your running ARCADE and CARTA sessions (start here if you have the _vos_ python package installed on your computer)

1. Get your security credentials (standard CADC certificate expires every 10 days) \
`cadc-get-cert -u {your_cadc_username}`\
(enter your password at the prompt)

2. List the access URLs of your current ARCADE and CARTA sessions\
`curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session`

3. Open the URL(s) in your local web browser

## Create your own ARCADE and CARTA sessions

**Easy (Recommended)**
Sessions are started (or resumed) using the `ARCADE` and `CARTA` buttons on the [demo CANFAR homepage](https://demo.canfar.net/en/). Ensure you are logged in to your CADC account at the top right of the page to enable these options.

**Advanced**
If the easy method above fails or you would rather use the command line to start/resume your sessions:

### ARCADE
1. Get your security credentials (standard CADC certificate expires every 10 days) \
`cadc-get-cert -u {your_cadc_username}`\
(enter your password at the prompt)

2. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session -d "name={my_session_name}" -d "type=desktop"`

where `{my_session_name}` is the name that you set for the session (e.g., `{your_cadc_username}-arcade`). This can be any string that you want, so long as it is a single string with no spaces.

### CARTA
To instead initiate a new CARTA session, the last part of the command would instead be `"type=carta"`.

2. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session -d name={my_session_name} -d type=carta`

You can then find the new session’s URL by running the same command as listed in Step 3 above:

3. `curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session`

## How to terminate your session using `curl`
**[WARNING - if you terminate your session you will lose unsaved work]** Once you have tripple checked you actually want to do this, you can terminate your session from the command line using:

`curl -E mycert.pem -X DELETE https://proto.canfar.net/arcade/session/{sessionID}`

where {sessionID} is the 8 character string that comes back from the session listing above.


# Transfering data into and out of ARCADE

There are two primary methods for transferring data into the ARCADE file system.

1. Mount the ARCADE File System using SSHFS (recommended)
2. CADC's `vos` tools commands

## How To Use SSHFS to Mount the ARCADE File System Over SSH and Use `rsync`

In many cases it can become cumbersome to transfer large files to and from ARCADE. Luckily there is a way to mount the ARCADE file system to your local computer so you can make changes on the fly and treat the ARCADE file system as local storage.

### Installing SSHFS
#### On Ubuntu/Debian

SSHFS is Linux-based software that needs to be installed on your local computer. On Ubuntu and Debian based systems it can be installed through `apt-get`.

`sudo apt-get install sshfs`

#### On Mac OSX

You can install SSHFS on Mac OSX (although it's often already there). You will need to download FUSE and SSHFS from the osxfuse site (https://osxfuse.github.io/).

### Mounting the Remote File System

The following instructions will work for both Ubuntu/Debian and OSX. Instructions for Windows systems can be found at the bottom of [this page](https://www.digitalocean.com/community/tutorials/how-to-use-sshfs-to-mount-remote-file-systems-over-ssh).

To start, we will need to create a local directory in which to mount ARCADE's file system, "cavern."

`mkdir $HOME/mnt/cavern`

Now we can mount the file system locally using the following command, based on which OS you are running. You will be asked for your CADC password during this step.

#### On Ubuntu/Debian

`sshfs -o port=64022 {your_cadc_username}@proto.canfar.net:/ $HOME/mnt/cavern`

#### On Mac OSX

`sshfs -o port=64022,defer_permissions {your_cadc_username}@proto.canfar.net:/ $HOME/mnt/cavern`

The `defer_permissions` option works around issues with OSX permission handling. See [here](https://github.com/osxfuse/osxfuse/wiki/Mount-options#default_permissions-and-defer_permissions) for more details.

### Syncing Local and ARCADE Directories with `rsync` Over SSH

`rsync`, which stands for "remote sync", is a remote and local file synchronization tool. It uses an algorithm that minimizes the amount of data copied by only moving the portions of files that have changed. Further `rsync` examples and docs are [here](https://www.digitalocean.com/community/tutorials/how-to-use-rsync-to-sync-local-and-remote-directories-on-a-vps).

Follow the steps above to install SSHFS and mount the ARCADE file system locally. Then perform the sync.

`rsync -vrltP source_dir $HOME/mnt/cavern/destination_dir/`

- `-v` increases verbosity
- `-r` recurses into directories
- `-l` copies symlinks as symlinks
- `-t` preserves modification times (see `man rsync` for more details on why this option prevents resending already transferred data when not using `-a`)
- `-P` keeps partially transferred files and shows progress during transfer

## How to set up CADC's `vos` tools to transfer from your local computer to arcade

These steps will get you setup to transfer from your local machine directly to ARCADE.

1. Install _vos tools_ on your local machine for the transfer (etc.) commands
    - Done per-user since it installs Python modules into your area
    - This command will create a `src` directory where it is run. You need to keep this directory around so make sure you to run the command where you do not mind having that directory stored.

`pip install -e 'git+https://github.com/andamian/vostools@s2737#egg=vostools&subdirectory=vos'`

2. Create the directory `~/.config/vos/` on your local machine.

3. Add a file to that directory called `vos-config` with the following contents.
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
4. Create a temporary local certificate to authenticate you on the server with the following command.
    - It will prompt you for a password which will be the same one as you use to login to the CADC website.

`cadc-get-cert -u {CADC_username}`

5. Test this has worked by creating a test directory in your home directory on ARCADE with the following command.

`vmkdir arc:home/{ARCADE_username}/start_test`

6. If it worked, celebrate by deleting that test directory with this command.

`vrmdir arc:home/{ARCADE_username}/start_test`

## How to verify data transferred to ARCADE

Regardless of how you transferred your data to ARCADE, it can be helpful to check that the copy on ARCADE is identical to the original.

Likely the most robust method of deteriming this is to calculate cryptographic hashes (checksums) from the data. If you have a single file (e.g. a tarfile) then you can just run the hasher on that file both locally and on ARCADE to make sure the outputs are the same.

`shasum -a 256 /path/to/data`

If you are trying to check a directory and all its contents were fully transferred then do the following. Since the actual check relies on the `sort` command, you first need to make sure the locale rules will be the same on your local machine and ARCADE. Running these commands on ARCADE and your local machine will ensure that.

```
OLD_LC_ALL=$LC_ALL
export LC_ALL=C
```

Next, move into the top level of the directory you want to test and run the following. Checking the output locally and on ARCADE are identical will tell you that all data was transferred.

`find . -type f -exec shasum -a 256 {} \; | sort -k 1 | shasum -a 256`

Finally, running this on your local machine and ARCADE will put the locale settings back to what they were before.

`export LC_ALL=$OLD_LC_ALL`

# How to manage Python environments with conda on ARCADE

Installing and managing the versions of your Python packages can be done with conda (e.g. working with a set of Python packages that you can export as a text list for others to exac
tly reproduce your Python environment). [Anaconda](https://docs.anaconda.com/) is installed on ARCADE at the system-wide level so you don't need to install your own (Ana)conda yours
elf. It should be possible to install (Ana)conda in your own directory, if you really like to have control, but this is likely not necessary for the majority of users and has not be
en confirmed on ARCADE yet.

If you just want to have access to most general scientific analysis Python packages, then the default environment that starts in the xterm windows will likely suit your needs. This
default environment, [astroconda](https://astroconda.readthedocs.io/en/latest/), comes with the standard Anaconda suite of packages plus it gives you access to STScI packages.

If you need/want specific versions of packages, or packages you need aren't included in astroconda, then you can create your own conda environments that only you can see on ARCADE.
The general instructions at the "[Managing environments](https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-environments.html)" page in the conda documentation should help you with this.

# How to use the clipboard to copy/paste into and out of ARCADE

[See pdf tutorial.](Clipboard_Tutorial.pdf)

