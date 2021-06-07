#!/bin/sh

# Writes to stdout an intro message for users of the topcat container,
# given a single argument on the command line,
# which is the location of the topcat-full.jar file.

tcjar=$1
tcvers=`topcat -version | grep -i TOPCAT.version | sed 's/.*ersion //'`
stiltsvers=`stilts -version | grep -i STILTS.version | sed 's/.*ersion //'`

echo "# This container includes TOPCAT v${tcvers} and STILTS v${stiltsvers}."
echo '# You can use commands "topcat" and "stilts".'
echo
