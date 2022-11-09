#!/bin/bash

pushd /opt
casaviewer=$(python3 -m casaviewer --app-path 2> /dev/null | grep casaviewer)
echo ${casaviewer}
${casaviewer} --appimage-extract 2> /dev/null
mv /opt/casa/bin/casaviewer /opt/casa/bin/casaviewer.old
ln -s /opt/squashfs-root/AppRun /opt/casa/bin/casaviewer
#ln -s /opt/squashfs-root/AppRun /usr/local/bin/casaviewer

#additional steps courtesy of CASA helpdesk
#(NB: casa6.4 and higher are using python3.6 -- 3.8 doesn't work for 6.5+,
# with the Docker installation in its current form)
mv /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage.orig
ln -s /opt/squashfs-root/usr/bin/casaviewer /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage

#additional steps below to fix a similar problem for plotms
# Right now, need to uncomment out each specific version for the first
# command to run.  Hopefully this can be tidied up to something similar to the
# first set of commands above

#---- Choose the correct line/version below & uncomment out ------
#(NB: python 3.6 for CASA6.4 and above)
/opt/casa-6.4.4-31-py3.6/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.5.0-15-py3.6/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.5.1-23-py3.6/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.5.2-26-py3.6/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#----------------

#the two commands below apply to all versions to finish fixing plotms
#(NB: python3.6)
mv /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage.orig
ln -s /opt/squashfs-root/usr/bin/casaplotms /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage

popd
