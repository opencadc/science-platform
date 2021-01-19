cavern and skaha kustomization configuration

* Put the servops client certificate in this directory with the name 'cadcproxy.pem'
* Not currently used, but if the nginx ingress was on a cadc domain, put files 'canfar.net.key' and 'canfar.net-completechain.pem' in this directory.  nginx needs the entire certificate chain in one file.  contatenate the top-level cert with the rest of the bundle into one file canfar.net-completechain.pem
* See sssd/README.md for certificate requirements
* See cavern/README.md for cavern sshd certificate requirements
* Apply on configuration with `kubectl apply -k .` from this directory.
