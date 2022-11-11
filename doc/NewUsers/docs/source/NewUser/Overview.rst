.. _overview:

Portal Overview
================

The CANFAR Science Portal allows users to access the tools and computing 
resources they need to conduct their research.  This page provides a brief
overview of the system, with a few important notes.

Containers
----------

Software packages (such as CASA for interferometry data) are run through
*software containers*, which come pre-packaged with all of the necessary
dependencies to run in a specific environment.  Each software container,
and the session that it is launched from (more on sessions below) can access
your same common set of files, but on the back-end, may be running on a different
computer in the cloud.  Advanced users are encouraged to help create and
maintain software containers which are useful for their research community,
to help broaden the set of useful tools that the Science Platform is able
to offer.  Containers may take some time (a few minutes) to load if they
have not previously been opened on the specific cloud computer previously
that they are being called on.  Please have patience!

Sessions
--------

Users may choose to interact with their data in several different types of 
sessions.  Each type is described briefly below.  Users can run a maximum
of three sessions, regardless of type, at the same time.  
Each session will run for
a maximum of 14 days, at which point any processes running in it will
terminate.  All of your files, etc, are preserved and will be accessible
if, e.g., you launch a new session.  While a session is active, you can
close your browser and re-open the session later from the Science Portal
landing page (on any computer), and all of your files, running processes,
etc, will be as you left them. 

* A :ref:`Desktop session <launch_desktop>` provides a linux desktop-like environment in which to interact with software containers; many commonly-used astronomy software packages are available in the 'astrosoftware' menu.
* A :ref:`Notebook session <launch_notebook>` provides a Jupyter Notebook style of environment in which to interact with your data and run software.
* A :ref:`CARTA session <launch_carta>` runs the CARTA image viewing software, which provides an efficient way to examine large 3-D data cubes.
* The Contributed session provides access to community-created tools, such as the CASTOR exposure time calculator.

Note that all new sessions are launched from the main Science Portal page,
https://www.canfar.net/science-portal/  You cannot, for example, launch
a Notebook session from within a Desktop session.  

All of your files are simultaneously accessible from all active sessions,
as well as several other modes such as this website: 
https://www.canfar.net/storage/arc/list/home and you may upload/download
files via the methods described :ref:`here <filetransfer>`. 


File Storage
------------

There are a few important points to note about the file storage system
on the Science Portal:

* CANFAR's `VOSpace <https://www.canfar.net/en/docs/storage/>`_ service should be used for long-term stable file storage, as it includes multiple backups at different geographical locations.

* Users can request space in both a personal home directory as well as a :ref:`'project' space <projectspace>`.  Use of project space for most analyses is highly encouraged, as there are mechanisms available for users to easily share access to files, etc with their collaborators there.




