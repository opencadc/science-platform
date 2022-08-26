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

popd
