"""Generate the LocalQueue name vectors that LaunchAssignmentTest checks.

This is an independent implementation of the frozen naming rule in
docs/specs/skaha-community-session-routing.md section 5. Run from skaha/:

    python3 tools/localqueue_name_vectors.py > src/test/resources/localqueue-name-vectors.json
"""

import base64
import hashlib
import json
import re
import struct
import sys

_DNS_1123_LABEL = re.compile(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?")


def local_queue_name(user: str, community: str) -> str:
    """Return the LocalQueue name for one exact User and Community pair."""
    readable = f"{user}-{community}-lq"
    if len(readable) <= 63 and _DNS_1123_LABEL.fullmatch(readable):
        return readable
    payload = b"skaha-localqueue-v1\x00"
    for part in (user, community):
        encoded = part.encode()
        payload += struct.pack(">I", len(encoded)) + encoded
    digest = base64.b32encode(hashlib.sha256(payload).digest()).decode().rstrip("=").lower()
    return f"skaha-{digest}-lq"


PAIRS = [
    ("bob", "cadc"),
    ("bob", "default"),
    ("Bob", "cadc"),
    ("bob", "Cadc"),
    ("bob_smith", "cadc"),
    ("bob.smith", "cadc"),
    ("bob_cadc", "x"),
    ("bob-x", "y"),
    ("bob", "x-y"),
    ("cadc", "bob"),
    ("Ab", "c"),
    ("A", "bc"),
    ("u" * 52, "default"),
    ("u" * 53, "default"),
    ("a" * 63, "default"),
    ("0", "default"),
]


if __name__ == "__main__":
    vectors = [
        {"user": user, "community": community, "localQueue": local_queue_name(user, community)}
        for user, community in PAIRS
    ]
    json.dump(vectors, sys.stdout, indent=2)
    sys.stdout.write("\n")
