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