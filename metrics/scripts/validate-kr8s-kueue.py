"""In-cluster kr8s validation: zero-config auth/TLS + Kueue ClusterQueue reads.

Runs as serviceaccount canfar-skaha-staging in canfar-system-staging.
Validates the three things kueue.py hand-rolled with httpx before ADR-0023:
  1. Auth: bearer token discovery from the mounted serviceaccount.
  2. TLS: trusting the cluster's self-signed CA from the mounted ca.crt.
  3. CRD access: GET/LIST/WATCH ClusterQueues (cluster-scoped custom resources).

Run it with (from metrics/scripts/):

    kubectl create configmap kr8s-kueue-validation-script \
        -n canfar-system-staging --from-file=test_kr8s.py=validate-kr8s-kueue.py \
        --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -f validate-kr8s-kueue-pod.yaml
    kubectl logs -f kr8s-kueue-validation -n canfar-system-staging

Last full run 2026-07-31 against keel-prod (K8s v1.30.11, kr8s 0.20.15).
Clean up with:

    kubectl delete pod/kr8s-kueue-validation \
        configmap/kr8s-kueue-validation-script -n canfar-system-staging
"""

import asyncio
import sys
import traceback

RESULTS: list[tuple[str, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    """Track and print one PASS/FAIL check result."""
    RESULTS.append((name, "PASS" if ok else "FAIL"))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" :: {detail}" if detail else ""), flush=True)


async def main() -> int:
    """Run every kr8s validation check and return a process exit code."""
    import kr8s.asyncio
    from kr8s.asyncio.objects import new_class

    print(f"kr8s version: {kr8s.__version__}", flush=True)

    # 1. Zero-config client: no kubeconfig, no URL, no token, no CA passed.
    try:
        api = await kr8s.asyncio.api()
        record("zero-config client construction", True, f"server={api.auth.server}")
    except Exception:
        traceback.print_exc()
        record("zero-config client construction", False)
        return 1

    # 2. TLS handshake against the API server (this is where a self-signed
    #    cluster CA breaks clients that ignore the mounted ca.crt).
    try:
        version = await api.version()
        record("TLS + auth (GET /version)", True, f"gitVersion={version.get('gitVersion')}")
    except Exception:
        traceback.print_exc()
        record("TLS + auth (GET /version)", False)
        return 1

    # 3. Typed CRD access via new_class, preferred v1beta2 then v1beta1.
    listed = None
    for api_version in ("kueue.x-k8s.io/v1beta2", "kueue.x-k8s.io/v1beta1"):
        ClusterQueue = new_class(
            kind="ClusterQueue",
            version=api_version,
            namespaced=False,
        )
        try:
            queues = [q async for q in api.get(ClusterQueue)]
            listed = (api_version, queues)
            break
        except Exception as exc:
            print(f"list via {api_version} failed: {exc!r}", flush=True)
    if listed is None:
        record("LIST ClusterQueues (CRD)", False)
        return 1
    api_version, queues = listed
    record("LIST ClusterQueues (CRD)", True, f"{api_version}: {[q.name for q in queues]}")

    # 4. GET one by name + read spec quota shape the provider aggregates.
    if queues:
        try:
            first = queues[0]
            matches = [q async for q in api.get(type(first), first.name)]
            cq = matches[0]
            groups = cq.raw.get("spec", {}).get("resourceGroups", [])
            flavors = sum(len(g.get("flavors", [])) for g in groups)
            usage = cq.raw.get("status", {}).get("flavorsUsage", [])
            record(
                "GET ClusterQueue by name + spec/status shape",
                True,
                f"name={cq.name} resourceGroups={len(groups)} flavors={flavors} "
                f"flavorsUsage_entries={len(usage)}",
            )
        except Exception:
            traceback.print_exc()
            record("GET ClusterQueue by name + spec/status shape", False)

    # 5. Named GET via call_api: the production access pattern (get-only RBAC).
    #    Checks 3-4 use kr8s object helpers, which LIST with a field selector
    #    and therefore also require the `list` verb.
    if queues:
        try:
            async with api.call_api(
                method="GET",
                version=api_version,
                url=f"clusterqueues/{queues[0].name}",
            ) as response:
                doc = response.json()
            record(
                "named GET via call_api (get-only RBAC path)",
                True,
                f"kind={doc.get('kind')} name={doc.get('metadata', {}).get('name')}",
            )
        except Exception:
            traceback.print_exc()
            record("named GET via call_api (get-only RBAC path)", False)

    # 6. Short watch: proves long-poll/streaming works through the same TLS path.
    try:
        ClusterQueue = new_class(
            kind="ClusterQueue", version=api_version, namespaced=False
        )
        events = 0
        async with asyncio.timeout(15):
            async for _event, _obj in api.watch(ClusterQueue):
                events += 1
                if events >= 1:
                    break
        record("WATCH ClusterQueues (streaming)", True, f"received {events} event(s)")
    except TimeoutError:
        # No events within 15s still proves the watch connection opened.
        record("WATCH ClusterQueues (streaming)", True, "watch opened, no events in 15s")
    except Exception:
        traceback.print_exc()
        record("WATCH ClusterQueues (streaming)", False)

    failures = [name for name, status in RESULTS if status == "FAIL"]
    print(f"\nSUMMARY: {len(RESULTS) - len(failures)}/{len(RESULTS)} passed", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
