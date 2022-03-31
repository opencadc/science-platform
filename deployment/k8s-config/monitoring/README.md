Monitoring Installation Instructions
====================================

Prequisites:
------------

Download helm binary and extract to a suitable location. The latest releases can be found here: https://github.com/helm/helm/releases

To download and extract the binary run:

    curl -O https://get.helm.sh/helm-v3.8.1-linux-amd64.tar.gz

    tar -zxvf helm-v3.8.1-linux-amd64.tar.gz

Add the grafana helm repo and update repo:

    helm repo add grafana https://grafana.github.io/helm-charts

    helm repo update

Ensure a suitable persistent volume (pv) is created before attempting to install grafana. The specifications of the pv should match the values in the **values-grafana.yaml** file. The relevent section is this:

    persistence:
      type: pvc
      enabled: true
      storageClassName: "\"\""
      accessModes:
        - ReadWriteOnce
      size: 10Gi

Install Grafana:
----------------

Once the pv is configured, continue with the install with:

    sh install-grafana.sh

If helm is not installed system wide, modify the install script to point to the helm binary.

If the the pod fails to start, or the pod or pvc are pending, ensure the pv isn't still bound to a previous helm install.

Uninstall Grafana:
------------------

To uninstall grafana, run:

    sh uninstall-grafana.sh

