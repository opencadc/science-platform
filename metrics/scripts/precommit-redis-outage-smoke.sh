#!/usr/bin/env bash
# Manual pre-commit / CI stage placeholder until Redis coordination lands
# (CADC-16070). Exits non-zero so the stage is never treated as vacuously green.
set -euo pipefail
echo "metrics Redis-outage smoke is owned by CADC-16070; refuse empty pass." >&2
exit 2
