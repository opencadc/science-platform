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


Install Loki:
-------------

To install loki run:

    sh install-loki.sh


Uninstall Grafana and loki::
------------------

To uninstall grafana, run:

    sh uninstall-grafana.sh

To uninstall loki, run:

    sh uninstall-loki.sh

Configure Grafana:
------------------

Determine the IP of the grafana pod using:

    kubectl --namespace cadc-loki get pod -o wide

Obtain the admin password using:

    kubectl get secret --namespace cadc-loki cadc-loki-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo

Now launch a new desktop session and login to the Grafana dashboard using the IP at port 3000 and admin credentials.

In the Grafana gui, navigate to the Configuration menu and add the prometheus data source:

    http://kube-prometheus-prometheus.kube-prometheus:9090/

Also add the loki data source:

    http:<IP of Loki pod>:3100


Next import the following dashboards (or use your own favorites):






