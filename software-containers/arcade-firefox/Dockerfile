# first open X11 to local connections
#   xhost +local:docker
# create the shared downloads directory
#   mkdir /tmp/downloads && chmod 777 /tmp/downloads # create /tmp/downloads
# add an entry to $HOME/.mozilla/firefox/profiles.ini
# and chown the profile directory for it to the guest user (id 8080)
# 
# then run with (eg)
#   docker run -it --net host \
#	-v /tmp/.X11-unix:/tmp/.X11-unix \
#       -v /tmp/downloads:/firefox/Downloads \
#	-v $HOME/.mozilla:/firefox/.mozilla \
#	-v /usr/lib/firefox-39.03:/usr/lib/firefox-latest \
#	-e DISPLAY=$DISPLAY \
#	--name Firefox \
#	ffdocker
#
# -i 					keep stdin in open
# -t 					allocate a pseudo-TTY
# --net host:				all container networking
#					comes from the host
#  -v /tmp/.X11-unix:/tmp/.X11-unix	mount the tmp dir with live X11 sockets
#					inside the container
#  -e DISPLAY=$DISPLAY			copy the DISPLAY envirnment variable
# --name Firefox			give our container a name (must be
#					locally unique)
# ffdocker				container to run
#
# August 2015

FROM ubuntu:16.04

LABEL maintainer="<hello@begriffin.com>"

ENV LANG en_US.UTF-8
ENV XTERM_LOCALE en_US.UTF-8
ENV LC_COLLATE C

# install Firefox to get dependencies, but then run the version mounted on
# the command line
# get xterm/vim for It's All Text plugin
RUN apt-get update && \
    apt-get install -y --no-install-recommends firefox vim-tiny xterm

# also create a "firefox" user with my group ID but different user
#RUN groupadd -g 1000 beg && \
#     useradd -g 1000 -u 8080 -d /firefox -m firefox

# For finding font packages:
#    dpkg --get-selections |grep font
#    (edit the "install" selector off each line)
#    for p in (each) ; do dpkg-query -L $p | grep -qi '[.]ttf$' && echo $p ; done
RUN apt-get install -y \
                    fonts-dejavu-core \
		    fonts-freefont-ttf \
		    fonts-guru \
		    fonts-guru-extra \
		    fonts-kacst \
		    fonts-kacst-one \
		    fonts-khmeros-core \
		    fonts-lao \
		    fonts-lato \
		    fonts-liberation \
		    fonts-lklug-sinhala \
		    fonts-lohit-guru \
		    fonts-nanum \
		    fonts-noto \
		    fonts-noto-cjk \
		    fonts-noto-hinted \
		    fonts-noto-mono \
		    fonts-noto-unhinted \
		    fonts-opensymbol \
		    fonts-sil-abyssinica \
		    fonts-sil-padauk \
		    fonts-stix \
		    fonts-symbola \
		    fonts-takao-pgothic \
		    fonts-thai-tlwg \
		    fonts-tibetan-machine \
		    fonts-tlwg-garuda \
		    fonts-tlwg-garuda-ttf \
		    fonts-tlwg-kinnari \
		    fonts-tlwg-kinnari-ttf \
		    fonts-tlwg-laksaman \
		    fonts-tlwg-laksaman-ttf \
		    fonts-tlwg-loma \
		    fonts-tlwg-loma-ttf \
		    fonts-tlwg-mono \
		    fonts-tlwg-mono-ttf \
		    fonts-tlwg-norasi \
		    fonts-tlwg-norasi-ttf \
		    fonts-tlwg-purisa \
		    fonts-tlwg-purisa-ttf \
		    fonts-tlwg-sawasdee \
		    fonts-tlwg-sawasdee-ttf \
		    fonts-tlwg-typewriter \
		    fonts-tlwg-typewriter-ttf \
		    fonts-tlwg-typist \
		    fonts-tlwg-typist-ttf \
		    fonts-tlwg-typo \
		    fonts-tlwg-typo-ttf \
		    fonts-tlwg-umpush \
		    fonts-tlwg-umpush-ttf \
		    fonts-tlwg-waree \
		    fonts-tlwg-waree-ttf 

# shell wrapper around xterm / vim for It's All Text
ADD alltext.xterm /usr/bin/alltext.xterm
RUN chmod 755 /usr/bin/alltext.xterm

#USER firefox

#ENTRYPOINT [ "/usr/lib/firefox-latest/firefox" ]
ENTRYPOINT [ "firefox" ]
CMD [ "-ProfileManager", "-no-remote" ]
