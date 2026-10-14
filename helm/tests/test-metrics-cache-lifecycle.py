#!/usr/bin/env python3
"""Exercise the chart's cache lifecycle through a disposable Argo CD installation.

Requires Docker, kind, kubectl, Helm and PyYAML. Run with:
uv run --no-project --with pyyaml==6.0.3 python helm/tests/test-metrics-cache-lifecycle.py
The cluster is deleted on exit. --kubeconfig uses an existing disposable cluster
and leaves it running; never point this test at a shared or production cluster.
The fixture deploys the production Secret, ServiceAccount, Services, NetworkPolicy,
and test hooks with two lightweight HTTP/key consumers, not the full application stack.
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
# Exceed the Metrics hash threshold while keeping existing Skaha names within DNS limits.
RELEASE = "canfar-skaha-long-production-release-name-1234"
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


def validate_shared_identity():
    command("helm", "dependency", "build", str(ROOT))
    required = [
        "--set",
        "metrics.clusterName=test-cluster",
        "--set-string",
        'metrics.env.METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES=["cq-test"]',
        "--set-string",
        'metrics.env.METRICS_PROVIDERS__KUEUE__NAMESPACES=["cache-lifecycle"]',
        "--set",
        "deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://example.org~cavern",
    ]
    cases = [
        ("default", [], RELEASE, 1),
        ("custom", ["--set", "serviceAccount.name=shared-custom"], "shared-custom", 1),
        (
            "existing",
            [
                "--set",
                "serviceAccount.create=false",
                "--set",
                "serviceAccount.name=shared-existing",
            ],
            "shared-existing",
            0,
        ),
        (
            "legacy Skaha name",
            ["--set", "deployment.skaha.serviceAccountName=shared-legacy"],
            "shared-legacy",
            1,
        ),
        (
            "obsolete Metrics override",
            ["--set", "metrics.serviceAccount.name=forbidden-metrics-only"],
            RELEASE,
            1,
        ),
    ]
    for label, arguments, expected, account_count in cases:
        documents = list(
            yaml.safe_load_all(
                command(
                    "helm",
                    "template",
                    RELEASE,
                    str(ROOT),
                    "--namespace",
                    NAMESPACE,
                    *required,
                    *arguments,
                )
            )
        )
        accounts = []
        workloads = []
        for document in documents:
            if (
                not document
                or document.get("metadata", {})
                .get("labels", {})
                .get("app.kubernetes.io/name")
                == "redis"
            ):
                continue
            kind = document["kind"]
            if kind == "ServiceAccount":
                accounts.append(document["metadata"]["name"])
            if kind == "Pod":
                pod = document["spec"]
            elif kind in ("Deployment", "Job"):
                pod = document["spec"]["template"]["spec"]
            elif kind == "CronJob":
                pod = document["spec"]["jobTemplate"]["spec"]["template"]["spec"]
            else:
                pod = None
            if pod is not None:
                workloads.append(document["metadata"]["name"])
                assert pod.get("serviceAccountName") == expected, (label, workloads[-1])
            if kind in ("RoleBinding", "ClusterRoleBinding"):
                for subject in document["subjects"]:
                    if subject["kind"] == "ServiceAccount":
                        assert (
                            subject["name"] == expected
                            and subject["namespace"] == NAMESPACE
                        )
        assert len(workloads) >= 6, (
            "Expected Skaha, Metrics, two cache Jobs, and two test Pods"
        )
        assert len(accounts) == account_count and all(
            name == expected for name in accounts
        )
        print(
            f"PASS: {label} uses one identity across all first-party workloads and RBAC",
            flush=True,
        )

    documents = list(
        yaml.safe_load_all(
            command(
                "helm",
                "template",
                RELEASE,
                str(ROOT),
                "--namespace",
                NAMESPACE,
                *required,
                "--set",
                "metrics.test.enabled=false",
                "--set",
                "metrics.networkPolicy.enabled=false",
            )
        )
    )
    assert not any(
        document
        and document["kind"] == "NetworkPolicy"
        and document["metadata"]["name"].endswith("skaha-metrics-api-networkpolicy")
        for document in documents
    )
    assert not any(
        document
        and document["kind"] == "Pod"
        and document["metadata"]["name"].endswith("metrics-test")
        for document in documents
    )
    print("PASS: disabled Metrics test and NetworkPolicy do not render", flush=True)


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

    # Copy production manifests verbatim; HTTP/key consumers stand in for application processes.
    chart = directory / "chart"
    (chart / "templates").mkdir(parents=True)
    metadata = yaml.safe_load((ROOT / "Chart.yaml").read_text())
    metadata.pop("dependencies")
    (chart / "Chart.yaml").write_text(yaml.safe_dump(metadata))
    values = yaml.safe_load((ROOT / "values.yaml").read_text())
    values["serviceAccount"]["name"] = "shared-skaha"
    (chart / "values.yaml").write_text(yaml.safe_dump(values))
    for name in [
        "_helpers.tpl",
        "metrics-cache-secret.yaml",
        "serviceaccount.yaml",
        "metrics-service.yaml",
        "metrics-networkpolicy.yaml",
        "skaha-tomcat-expose.yaml",
    ]:
        shutil.copy(ROOT / "templates" / name, chart / "templates" / name)
    (chart / "templates/tests").mkdir()
    for name in ["test-metrics.yaml", "test-connection.yaml"]:
        shutil.copy(ROOT / "templates/tests" / name, chart / "templates/tests" / name)
    (chart / "templates/consumer.yaml").write_text("""
{{- range $role := list "skaha" "metrics" }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: key-consumer-{{ $role }}
  labels:
    app: key-consumer
spec:
  replicas: 1
  selector:
    matchLabels:
      app: key-consumer
      role: {{ $role }}
  template:
    metadata:
      labels:
        app: key-consumer
        role: {{ $role }}
        {{- if eq $role "metrics" }}
        {{- include "skaha.metrics.selectorLabels" $ | nindent 8 }}
        {{- else }}
        run: {{ $.Release.Name }}-skaha-tomcat
        {{- end }}
    spec:
      serviceAccountName: {{ include "skaha.serviceAccountName" $ }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
      containers:
        - name: consumer
          image: "{{ $.Values.metrics.image.repository }}:{{ $.Values.metrics.image.tag }}"
          command: ["python", "-c"]
          args:
            - |
              import base64, hashlib, http.server, json, os, pathlib
              value = hashlib.sha256(os.environ["KEY"].encode()).hexdigest()
              pathlib.Path("/tmp/key-fingerprint").write_text(value)
              token = pathlib.Path("/var/run/secrets/kubernetes.io/serviceaccount/token").read_text().split(".")[1]
              subject = json.loads(base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)))["sub"]
              pathlib.Path("/tmp/identity").write_text(subject)
              class Handler(http.server.BaseHTTPRequestHandler):
                  def do_GET(self):
                      self.send_response(200)
                      self.end_headers()
                  def log_message(self, *args):
                      pass
              http.server.ThreadingHTTPServer(("", int(os.environ["PORT"])), Handler).serve_forever()
          ports:
            - name: http
              containerPort: {{ if eq $role "metrics" }}8000{{ else }}8080{{ end }}
          env:
            - name: PORT
              value: {{ if eq $role "metrics" }}"8000"{{ else }}"8080"{{ end }}
            - name: KEY
              valueFrom:
                secretKeyRef:
                  name: {{ include "skaha.metrics.cacheSecretName" $ }}
                  key: {{ include "skaha.metrics.cacheSecretKey" $ }}
          readinessProbe:
            exec:
              command: ["python", "-c", "from pathlib import Path; assert Path('/tmp/key-fingerprint').exists()"]
            periodSeconds: 2
{{- end }}
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
    image = yaml.safe_load((ROOT / "values.yaml").read_text())["metrics"][
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
    application["spec"]["ignoreDifferences"] = [
        {
            "group": "",
            "kind": "Secret",
            "name": cache_secret_name,
            "namespace": NAMESPACE,
            "jsonPointers": ["/data/key-secret"],
        }
    ]
    application["spec"]["syncPolicy"]["syncOptions"].append(
        "RespectIgnoreDifferences=true"
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
    pods = json.loads(
        kubectl("get", "pods", "-n", NAMESPACE, "-l", "app=key-consumer", "-o", "json")
    )["items"]
    for pod in pods:
        assert pod["spec"]["serviceAccountName"] == "shared-skaha"
        identity = kubectl(
            "exec",
            "-n",
            NAMESPACE,
            pod["metadata"]["name"],
            "--",
            "cat",
            "/tmp/identity",
        )
        assert identity == f"system:serviceaccount:{NAMESPACE}:shared-skaha"
    print(
        "PASS: both running replicas have the shared Skaha ServiceAccount token identity",
        flush=True,
    )

    # Argo CD skips Helm test hooks. Explicitly execute the rendered Metrics test Pod here.
    hook = yaml.safe_load(
        command(
            "helm",
            "template",
            RELEASE,
            str(chart),
            "--namespace",
            NAMESPACE,
            "--show-only",
            "templates/tests/test-metrics.yaml",
        )
    )
    apply(hook, NAMESPACE)
    kubectl(
        "wait",
        "-n",
        NAMESPACE,
        f"pod/{hook['metadata']['name']}",
        "--for=jsonpath={.status.phase}=Succeeded",
        "--timeout=120s",
    )
    assert (
        json.loads(
            kubectl(
                "get", "pod", hook["metadata"]["name"], "-n", NAMESPACE, "-o", "json"
            )
        )["spec"]["serviceAccountName"]
        == "shared-skaha"
    )
    print(
        "PASS: Metrics test hook uses the shared ServiceAccount and reaches the live fixture Service",
        flush=True,
    )
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

    kubectl(
        "rollout", "restart", "deployment", "-n", NAMESPACE, "-l", "app=key-consumer"
    )
    for role in ("skaha", "metrics"):
        kubectl(
            "rollout",
            "status",
            f"deployment/key-consumer-{role}",
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

    # Verify Helm itself executes both production test hooks against a native Helm release.
    native_namespace = "helm-test-hooks"
    command(
        "helm",
        "install",
        "helm-test",
        str(chart),
        "--namespace",
        native_namespace,
        "--create-namespace",
        "--kubeconfig",
        str(kubeconfig),
        "--wait",
        "--timeout",
        "120s",
    )
    command(
        "helm",
        "test",
        "helm-test",
        "--namespace",
        native_namespace,
        "--kubeconfig",
        str(kubeconfig),
        "--timeout",
        "120s",
    )
    command(
        "helm",
        "uninstall",
        "helm-test",
        "--namespace",
        native_namespace,
        "--kubeconfig",
        str(kubeconfig),
    )
    print(
        "PASS: helm test executes both readiness/connection hooks in the live fixture cluster",
        flush=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", type=Path)
    parser.add_argument("--render-only", action="store_true")
    arguments = parser.parse_args()
    validate_shared_identity()
    if arguments.render_only:
        return
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
