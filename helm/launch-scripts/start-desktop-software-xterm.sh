#!/bin/bash
#
# xterm launcher invoked by start-desktop-software.sh.
#
# When a container provides /skaha/startup.sh, that script is called with this
# file as its sole argument. Image startup scripts often use unquoted "exec $@",
# which re-splits arguments on spaces; keeping the xterm command here avoids
# breaking options such as "-xrm XTerm*bellIsUrgent: false".
#
# Expects SKAHA_XTERM_TITLE to be set by start-desktop-software.sh.

exec xterm -fg white -bg black -title "${SKAHA_XTERM_TITLE:-xterm}" \
    -xrm 'XTerm*bellIsUrgent: false' \
    -xrm 'XTerm*bellIsAudible: false'
