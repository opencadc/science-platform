#!/usr/bin/env python3
"""Exercise the chart's cache lifecycle through a disposable Argo CD installation.

Requires Docker, kind, kubectl, Helm and PyYAML. Run with:
uv run --no-project --with pyyaml==6.0.3 python helm/tests/test-metrics-cache-lifecycle.py
The cluster is deleted on exit. --kubeconfig uses an existing disposable cluster
and leaves it running; never point this test at a shared or production cluster.
Only the chart-owned Secret and two key-consuming replicas are deployed.
"""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import yaml


ROOT = Path(__file__).resolve().parents[1]
ARGO_VERSION = "v3.5.5"
APP = "cache-lifecycle"
RELEASE = "canfar-skaha-long-production-release-name-1234567890"
NAMESPACE = "cache-lifecycle"


def command(*args, input=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr}")
    return result.stdout


def wait_for(description, predicate, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            print(f"PASS: {description}", flush=True)
            return
        time.sleep(2)
    raise AssertionError(f"Timed out: {description}")


def run(kubeconfig, directory):
    def kubectl(*args, input=None):
        return command("kubectl", "--kubeconfig", str(kubeconfig), *args, input=input)

    def apply(resource, namespace="argocd"):
        kubectl("apply", "-n", namespace, "-f", "-", input=yaml.safe_dump(resource))

    context = kubectl("config", "current-context").strip()
    local_contexts = {
        f"kind-{cluster}" for cluster in command("kind", "get", "clusters").splitlines()
    }
    if context not in local_contexts:
        raise ValueError("Lifecycle tests require a local kind cluster")
    if (
        "applications.argoproj.io"
        in kubectl("api-resources", "-o", "name").splitlines()
    ):
        existing_application = kubectl(
            "get",
            "application",
            APP,
            "-n",
            "argocd",
            "--ignore-not-found",
            "-o",
            "name",
        )
        if existing_application:
            raise ValueError(
                "Use a fresh cluster: the lifecycle test Application already exists"
            )

    apply({"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": "argocd"}})
    kubectl(
        "apply",
        "--server-side",
        "--force-conflicts",
        "-n",
        "argocd",
        "-f",
        f"https://raw.githubusercontent.com/argoproj/argo-cd/{ARGO_VERSION}/manifests/core-install.yaml",
    )
    kubectl(
        "rollout",
        "status",
        "-n",
        "argocd",
        "deployment/argocd-repo-server",
        "--timeout=240s",
    )
    kubectl(
        "rollout",
        "status",
        "-n",
        "argocd",
        "statefulset/argocd-application-controller",
        "--timeout=240s",
    )
    apply(
        {
            "apiVersion": "argoproj.io/v1alpha1",
            "kind": "AppProject",
            "metadata": {"name": "default"},
            "spec": {
                "sourceRepos": ["*"],
                "destinations": [{"server": "*", "namespace": "*"}],
                "clusterResourceWhitelist": [{"group": "*", "kind": "*"}],
            },
        }
    )

    # Copy production templates verbatim. Other Skaha/Redis/Kueue workloads are outside this test.
    chart = directory / "chart"
    (chart / "templates").mkdir(parents=True)
    metadata = yaml.safe_load((ROOT / "Chart.yaml").read_text())
    metadata.pop("dependencies")
    (chart / "Chart.yaml").write_text(yaml.safe_dump(metadata))
    shutil.copy(ROOT / "values.yaml", chart / "values.yaml")
    for name in ["_helpers.tpl", "metricsBackend-cache-secret.yaml"]:
        shutil.copy(ROOT / "templates" / name, chart / "templates" / name)
    (chart / "templates/consumer.yaml").write_text("""
apiVersion: apps/v1
kind: Deployment
metadata:
  name: key-consumer
spec:
  replicas: 2
  selector:
    matchLabels:
      app: key-consumer
  template:
    metadata:
      labels:
        app: key-consumer
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
      containers:
        - name: consumer
          image: "{{ .Values.metricsBackend.image.repository }}:{{ .Values.metricsBackend.image.tag }}"
          command: ["python", "-c"]
          args:
            - |
              import hashlib, os, pathlib, time
              value = hashlib.sha256(os.environ["KEY"].encode()).hexdigest()
              pathlib.Path("/tmp/key-fingerprint").write_text(value)
              time.sleep(86400)
          env:
            - name: KEY
              valueFrom:
                secretKeyRef:
                  name: {{ include "skaha.metricsBackend.cacheSecretName" . }}
                  key: {{ include "skaha.metricsBackend.cacheSecretKey" . }}
          readinessProbe:
            exec:
              command: ["python", "-c", "from pathlib import Path; assert Path('/tmp/key-fingerprint').exists()"]
            periodSeconds: 2
""")
    repository_name = f"cache-chart-{time.time_ns()}"
    repository = directory / "repository"
    repository.mkdir()
    command("helm", "package", str(chart), "--destination", str(repository))
    command("helm", "repo", "index", str(repository))
    configmap = json.loads(
        kubectl(
            "create",
            "configmap",
            repository_name,
            "-n",
            "argocd",
            f"--from-file={repository}",
            "--dry-run=client",
            "-o",
            "json",
        )
    )
    apply(configmap)
    image = yaml.safe_load((ROOT / "values.yaml").read_text())["metricsBackend"][
        "image"
    ]
    apply(
        {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {"name": repository_name},
            "spec": {
                "replicas": 1,
                "selector": {"matchLabels": {"app": repository_name}},
                "template": {
                    "metadata": {"labels": {"app": repository_name}},
                    "spec": {
                        "containers": [
                            {
                                "name": "http",
                                "image": f"{image['repository']}:{image['tag']}",
                                "command": [
                                    "python",
                                    "-m",
                                    "http.server",
                                    "8080",
                                    "--directory",
                                    "/charts",
                                ],
                                "ports": [{"containerPort": 8080}],
                                "volumeMounts": [
                                    {"name": "charts", "mountPath": "/charts"}
                                ],
                            }
                        ],
                        "volumes": [
                            {"name": "charts", "configMap": {"name": repository_name}}
                        ],
                    },
                },
            },
        }
    )
    apply(
        {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": repository_name},
            "spec": {"selector": {"app": repository_name}, "ports": [{"port": 8080}]},
        }
    )
    kubectl(
        "rollout",
        "status",
        "-n",
        "argocd",
        f"deployment/{repository_name}",
        "--timeout=240s",
    )

    application = {
        "apiVersion": "argoproj.io/v1alpha1",
        "kind": "Application",
        "metadata": {
            "name": APP,
            "finalizers": ["resources-finalizer.argocd.argoproj.io"],
        },
        "spec": {
            "project": "default",
            "source": {
                "repoURL": f"http://{repository_name}.argocd.svc:8080",
                "chart": metadata["name"],
                "targetRevision": metadata["version"],
                "helm": {"releaseName": RELEASE},
            },
            "destination": {
                "server": "https://kubernetes.default.svc",
                "namespace": NAMESPACE,
            },
            "syncPolicy": {
                "automated": {"prune": True, "selfHeal": True},
                "syncOptions": ["CreateNamespace=true"],
            },
        },
    }

    rendered = yaml.safe_load_all(
        command("helm", "template", RELEASE, str(chart), "--namespace", NAMESPACE)
    )
    cache_secret_name = next(
        document["metadata"]["name"]
        for document in rendered
        if document["kind"] == "Secret"
    )
    assert cache_secret_name != f"{RELEASE}-skaha-metrics-cache", (
        "Expected the long-release name regression to exercise hashing"
    )
    configuration = (ROOT / "argocd/metrics-cache-secret.patch.yaml").read_text()
    configuration = configuration.replace(
        "CACHE_SECRET_NAME", cache_secret_name
    ).replace("RELEASE_NAMESPACE", NAMESPACE)
    configuration = yaml.safe_load(configuration)["spec"]
    application["spec"]["ignoreDifferences"] = configuration["ignoreDifferences"]
    application["spec"]["syncPolicy"]["syncOptions"].extend(
        configuration["syncPolicy"]["syncOptions"]
    )

    def sync():
        # The Argo CD API copies these Application options into each manual operation.
        kubectl(
            "patch",
            "application",
            APP,
            "-n",
            "argocd",
            "--type=merge",
            "-p",
            json.dumps(
                {
                    "operation": {
                        "sync": {
                            "prune": True,
                            "syncOptions": application["spec"]["syncPolicy"][
                                "syncOptions"
                            ],
                        }
                    }
                }
            ),
        )

    def status():
        return json.loads(
            kubectl("get", "application", APP, "-n", "argocd", "-o", "json")
        )

    def healthy():
        current = status()
        phase = current.get("status", {}).get("operationState", {}).get("phase")
        if phase in ("Failed", "Error"):
            raise AssertionError(current["status"]["operationState"].get("message"))
        return (
            phase == "Succeeded"
            and current["status"].get("sync", {}).get("status") == "Synced"
            and current["status"].get("health", {}).get("status") == "Healthy"
        )

    def secret():
        return json.loads(
            kubectl(
                "get",
                "secret",
                cache_secret_name,
                "-n",
                NAMESPACE,
                "-o",
                "json",
            )
        )

    def fingerprints():
        pods = json.loads(
            kubectl(
                "get", "pods", "-n", NAMESPACE, "-l", "app=key-consumer", "-o", "json"
            )
        )["items"]
        return [
            kubectl(
                "exec",
                "-n",
                NAMESPACE,
                pod["metadata"]["name"],
                "--",
                "cat",
                "/tmp/key-fingerprint",
            )
            for pod in pods
            if not pod["metadata"].get("deletionTimestamp")
        ]

    apply(application)
    wait_for("Argo CD initial full sync is Healthy and Synced", healthy)
    first = secret()
    expected = hashlib.sha256(base64.b64decode(first["data"]["key-secret"])).hexdigest()
    assert first["immutable"] is True
    assert fingerprints() == [expected, expected]
    for iteration in range(3):
        previous = status()["status"]["reconciledAt"]
        kubectl(
            "annotate",
            "application",
            APP,
            "-n",
            "argocd",
            "--overwrite",
            "argocd.argoproj.io/refresh=hard",
        )
        wait_for(
            f"hard refresh {iteration + 1} stays Healthy and Synced",
            lambda: status()["status"]["reconciledAt"] != previous and healthy(),
        )
        assert secret()["metadata"]["uid"] == first["metadata"]["uid"]
        assert secret()["data"] == first["data"]
        previous = status()["status"]["operationState"]["startedAt"]
        sync()
        wait_for(
            f"full sync {iteration + 1} preserves the key",
            lambda: (
                status()["status"]["operationState"]["startedAt"] != previous
                and healthy()
            ),
        )
        assert secret()["metadata"]["uid"] == first["metadata"]["uid"]
        assert secret()["data"] == first["data"]

    kubectl("rollout", "restart", "deployment/key-consumer", "-n", NAMESPACE)
    kubectl(
        "rollout",
        "status",
        "deployment/key-consumer",
        "-n",
        NAMESPACE,
        "--timeout=120s",
    )
    assert fingerprints() == [expected, expected]
    print("PASS: replacement replicas use the same key", flush=True)

    # Non-cascading deletion intentionally retains the installation and key.
    kubectl(
        "patch",
        "application",
        APP,
        "-n",
        "argocd",
        "--type=merge",
        "-p",
        '{"metadata":{"finalizers":[]}}',
    )
    kubectl(
        "delete", "application", APP, "-n", "argocd", "--wait=true", "--timeout=120s"
    )
    assert secret()["data"] == first["data"]
    apply(application)
    sync()
    wait_for("reattaching a non-cascaded installation preserves the key", healthy)
    assert secret()["metadata"]["uid"] == first["metadata"]["uid"]
    assert secret()["data"] == first["data"]

    kubectl(
        "delete", "application", APP, "-n", "argocd", "--wait=true", "--timeout=120s"
    )
    wait_for(
        "cascading uninstall deletes the Secret",
        lambda: (
            not kubectl(
                "get",
                "secret",
                cache_secret_name,
                "-n",
                NAMESPACE,
                "--ignore-not-found",
                "-o",
                "name",
            )
        ),
    )
    apply(application)
    wait_for("fresh Argo CD installation is Healthy and Synced", healthy)
    second = secret()
    assert second["metadata"]["uid"] != first["metadata"]["uid"]
    assert second["data"] != first["data"]
    expected = hashlib.sha256(
        base64.b64decode(second["data"]["key-secret"])
    ).hexdigest()
    assert fingerprints() == [expected, expected]
    print("PASS: fresh installation generates a new key for both replicas", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", type=Path)
    arguments = parser.parse_args()
    cluster = "skaha-cache-lifecycle-test"
    with tempfile.TemporaryDirectory(prefix="skaha-cache-lifecycle-") as temp:
        directory = Path(temp)
        kubeconfig = arguments.kubeconfig or directory / "kubeconfig"
        if not arguments.kubeconfig:
            command(
                "kind",
                "create",
                "cluster",
                "--name",
                cluster,
                "--kubeconfig",
                str(kubeconfig),
                "--wait",
                "60s",
            )
        try:
            run(kubeconfig, directory)
        finally:
            if not arguments.kubeconfig:
                command(
                    "kind",
                    "delete",
                    "cluster",
                    "--name",
                    cluster,
                    "--kubeconfig",
                    str(kubeconfig),
                )


if __name__ == "__main__":
    main()
