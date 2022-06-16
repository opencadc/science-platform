#!/bin/bash

echo "[dragons] start init.sh"
echo "[dragons] start source activate dragons"
source activate dragons
echo "[dragons] end source activate dragons"

# To make container useful in both interactive sessions
# and batch processing, check if xterm is available.
# If so, start up xterm. If not, get ready for
# incoming processing command.
if xhost >& /dev/null; then 
	# Display exists
	xterm -T $1
else 
	# Display invalid"
	exec "$@"
fi
echo "[dragons] end init.sh"