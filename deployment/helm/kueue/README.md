# Kueue's helm chart

## Table of Contents

<!-- toc -->
- [Installation](#installation)
  - [Prerequisites](#prerequisites)
  - [Installing the chart](#installing-the-chart)
    - [Install chart using Helm v3.0+](#install-chart-using-helm-v30)
    - [Verify that controller pods are running properly.](#verify-that-controller-pods-are-running-properly)
<!-- /toc -->


### Installation

#### Prerequisites

- [Helm](https://helm.sh/docs/intro/quickstart/#install-helm)

#### Installing the chart

##### Install chart using Helm v3.0+

```
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install --create-namespace --namespace kueue-system --values ./kueue/values.yaml <name> ./kueue
```

##### Verify that controller pods are running properly.

```bash
$ kubectl get deploy -n kueue-system
NAME                           READY   UP-TO-DATE   AVAILABLE   AGE
kueue-controller-manager       1/1     1            1           7s
```
