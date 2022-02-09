# Ingress-service-cleanup
This tool removes orphaned skaha service sessions and ingress sessions. A session is considered orphaned if there is no pod corresponding to the service or ingress session. This can happen when sessions are exited through a native shutdown function, rather than through the skaha API.
