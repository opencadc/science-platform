#!/bin/bash

# One argument expected: the skaha session ID, which makes up part of the connect url
jupyter lab --ServerApp.ip=0.0.0.0 --ServerApp.port=8888 --no-browser --ServerApp.base_url=notebook/"$1" --ServerApp.root_dir=/ --ServerApp.allow_origin="*" 
