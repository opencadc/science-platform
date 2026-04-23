"""Pure parsing helpers for Kueue ``spec.resourceGroups`` structures.

Intent
------
Both the **platform map** path (arbitrary resource names → capacity strings)
and the **legacy capacity reading** path (CPU + memory floats for user/session
snapshots) need the same definition of *nominal quota* summed from Kueue YAML.

Keeping parsing here avoids silently divergent math when one code path is
updated and the other is not. JSON shapes follow Kueue v1beta2 CRDs: each
``resourceGroups`` entry lists ``flavors``, each flavor lists ``resources`` with
``nominalQuota`` quantities compatible with Kubernetes resource.Quantity syntax.
"""

from __future__ import annotations

from typing import Any

from metrics.quantity import merge_resource_totals, parse_resource_amount


def sum_nominal_quotas_by_resource(doc: dict[str, Any]) -> dict[str, float]:
    """Sum ``nominalQuota`` for every resource across all groups and flavors.

    Resource **names** are taken verbatim from the API (for example ``cpu``,
    ``memory``, ``nvidia.com/gpu``) so the platform contract can surface future
    resource types without schema changes. Values are accumulated in internal
    float units: cores for CPU, gibibytes for memory, raw float for unknown names
    (see :func:`metrics.quantity.parse_resource_amount`).

    Args:
        doc: A ``ClusterQueue`` or ``Cohort`` API object (dict with ``spec``).

    Returns:
        Mapping of resource name → aggregated float suitable for formatting back
        to Kubernetes-style quantity strings.
    """
    totals: dict[str, float] = {}
    spec = doc.get("spec") or {}
    for group in spec.get("resourceGroups") or []:
        for flavor in group.get("flavors") or []:
            for resource in flavor.get("resources") or []:
                name = str(resource.get("name", "")).strip()
                if not name:
                    continue
                quota = resource.get("nominalQuota")
                merge_resource_totals(
                    totals,
                    name,
                    parse_resource_amount(
                        name, str(quota) if quota is not None else ""
                    ),
                )
    return totals
