export PS1="${debian_chroot:+($debian_chroot)}\u \W \$ "
. /opt/conda/etc/profile.d/mamba.sh

conda activate base
eval "$(command conda shell.bash hook 2> /dev/null)"

