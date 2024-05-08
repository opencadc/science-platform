#!/bin/sh -e

cat <<EOF >> /etc/ssh/sshd_config
Match User *
ChrootDirectory /cavern
X11Forwarding no
AllowTcpForwarding no
PermitTTY no
ForceCommand internal-sftp
EOF
