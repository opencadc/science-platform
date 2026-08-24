#!/usr/bin/env bash
# Manual pre-commit / CI stage placeholder until OTel platform slice lands
# (CADC-16071). Exits non-zero so the stage is never treated as vacuously green.
set -euo pipefail
echo "metrics OTel smoke is owned by CADC-16071; refuse empty pass." >&2
exit 2
