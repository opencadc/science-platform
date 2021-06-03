
* Until we do SSL termination for cavern in the ingress, the ssl version of the ingress config is not used.  Put the host certificate of all hosts that need to be added to the trusted ca bundle in the cacerts directory.  For nginx ingress, concatenate all the cacerts into a file called all.crt

