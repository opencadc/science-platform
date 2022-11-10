.. _vostools:

Using VOS Tools
===============

The most efficient way to transfer files in and out of CANFAR's Science Portal is to use
the VOS Tools, which are also used for interacting with CANFAR's VOSpace.

Instructions for installing VOS Tools on your personal computer are 
located `here <https://www.canfar.net/en/docs/storage/>`_
under the section titled *The vos Python module and command line client*.

Instructions on how to use this tool, including some basic examples, are
found on the same webpage.  In brief, this tool runs on the command line
with syntax similar to the linux 'scp' command.  File locations within
CANFAR systems are specified with *vos* for VOSpace and *arc* for the Science Portal.
For example, to copy a file from your personal computer to your home
directory in the Science Portal, you would type the following command on your 
personal computer::

   vcp myfile.txt arc:home/[username]

To copy a file from VOSpace to your personal computer, you would use::

   vcp vos:[username]/myfile.txt ./

To copy files from the Science Portal to VOSpace, you would similarly use the command::

  vcp myfile.txt vos:[username]

with the command being run on a terminal *within the Science Portal*.  Note that it is 
not yet possible to initiate file transfers between the Science Portal and VOSpace from your
personal computer.

Also, you may have noticed that the base directory structure differs slightly 
between VOSpace
and the Science Portal; the Science Portal includes a 'home' directory, while VOSpace does not.
Other commands such as *vls* to list files and *vrm* to remove/delete
files may also be useful.
Note that VOS Tools use a security certificate which needs to be updated 
periodically.  If you get an error message stating::

   ERROR:: Expired cert. Update by running cadc-get-cert

run the following command on your personal computer::

   cadc-get-cert -u [username]

and enter your password for CADC/CANFAR services at the prompt.
More information about VOS Tools can be found at:
https://www.canfar.net/en/docs/storage/
