Monitoring Installation Instructions


Prequisites:

Download helm binary and extract to a suitable location:

  https://github.com/helm/helm/releases

  curl -O https://get.helm.sh/helm-v3.8.1-linux-amd64.tar.gz

  tar -zxvf helm-v3.8.1-linux-amd64.tar.gz

Add the grafana helm repo and update repo:

  helm repo add grafana https://grafana.github.io/helm-charts

  helm repo update


