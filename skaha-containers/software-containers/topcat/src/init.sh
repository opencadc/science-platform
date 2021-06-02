#!/bin/sh
echo 'TOPCAT container: use commands "topcat" or "stilts".'
topcat -version | grep -i 'TOPCAT version'
stilts -version | grep -i 'STILTS version'
