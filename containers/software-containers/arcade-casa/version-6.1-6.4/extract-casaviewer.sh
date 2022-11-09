#!/bin/bash

pushd /opt
casaviewer=$(python3 -m casaviewer --app-path 2> /dev/null | grep casaviewer)
echo ${casaviewer}
${casaviewer} --appimage-extract 2> /dev/null
mv /opt/casa/bin/casaviewer /opt/casa/bin/casaviewer.old
ln -s /opt/squashfs-root/AppRun /opt/casa/bin/casaviewer
#ln -s /opt/squashfs-root/AppRun /usr/local/bin/casaviewer

#additional steps courtesy of CASA helpdesk
mv /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage.orig
ln -s /opt/squashfs-root/usr/bin/casaviewer /opt/casa/lib/py/lib/python3.6/site-packages/casaviewer/__bin__/casaviewer-x86_64.AppImage

#additional steps below to fix a similar problem for plotms
# Right now, need to uncomment out each specific version for the first
# command to run.  Hopefully this can be tidied up to something similar to the
# first set of commands above

#---- Choose the correct line/version below & uncomment out ------
#/opt/casa-6.1.0-118/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.2.0-124/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.3.0-48/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
/opt/casa-6.4.1-12-pipeline-2022.2.0.64/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.2.1-7-pipeline-2021.2.0.128/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.1.1-15-pipeline-2020.1.0.40/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.4.0-16/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.4.3-27/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#/opt/casa-6.4.4-31/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage --appimage-extract 2> /dev/null
#----------------

#the two commands below apply to all versions to finish fixing plotms
mv /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage.orig
ln -s /opt/squashfs-root/usr/bin/casaplotms /opt/casa/lib/py/lib/python3.6/site-packages/casaplotms/__bin__/casaplotms-x86_64.AppImage

popd
