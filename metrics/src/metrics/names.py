"""Kubernetes name grammars shared by settings, routes, and providers."""

from __future__ import annotations

import re

LABEL_VALUE_PATTERN = r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$"
"""A Kubernetes label value: every Metrics path subject and the platform name."""

LABEL_VALUE = re.compile(LABEL_VALUE_PATTERN)

DNS_LABEL = re.compile(r"^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$")
"""An RFC 1123 DNS label: a namespace, or one part of a DNS subdomain."""
