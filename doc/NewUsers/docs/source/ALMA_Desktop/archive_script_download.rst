.. _desktop_script_download:

Download data from the ALMA archive (transfer script)
======================================================

Downloading data from the ALMA archive directly using a web
query is shown in :ref:`this tutorial<desktop_download>`.
Some users, however, may prefer to interact with the ALMA
archive on their local machine and instead transfer the
resulting data download script to their Desktop session.
For more information on using the archive query effectively, see this
instructional video: 
https://almascience.nrao.edu/alma-data/archive/archive-video-tutorials

Once you have a data download script on your local computer, there are a 
variety of methods to transfer files, 
which are outlined below in order of increasing technical skill level.

* :ref:`transfer the script by direct upload or copy/paste within a Notebook session<notebook_transfer_file>`
* upload the script via the web browser interface with ARCADE's storage space at https://www.canfar.net/storage/arc/list/home (NB: this is the same type of interface as the CANFAR VOSpace)
* copy the script using :ref:`VOS tools<vostools>`
* mount ARCADE's storage space on your personal machine using vofs, then copy the script (see https://github.com/opencadc/vostools/tree/master/vofs for documentation) 

Once the script is uploaded and thus accessible to your Desktop session, you 
can run the script within a terminal container and download the data, as 
shown in 
:ref:`this tutorial<desktop_run_download_script>`.

