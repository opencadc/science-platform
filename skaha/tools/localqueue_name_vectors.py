#!/usr/bin/env python3
"""Write LocalQueue name vectors for LaunchAssignmentTest.

The digest input is the ASCII bytes of skaha-localqueue-v1, one zero byte,
then the user and the community. Each string is a big-endian uint32 byte
length followed by its UTF-8 bytes. From the skaha directory:

    python3 tools/localqueue_name_vectors.py
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import struct
from pathlib import Path

_DNS_1123_LABEL = re.compile(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?")
_DIGEST_NAME = re.compile(r"skaha-[a-z2-7]{52}-lq")
_OUT = Path(__file__).resolve().parents[1] / "src/test/resources/localqueue-name-vectors.json"

# Published locks. A change to the encoding must fail here before it rewrites the file.
_KNOWN = {
    ("bob", "cadc"): "bob-cadc-lq",
    ("bob", "default"): "bob-default-lq",
    ("Bob", "cadc"): "skaha-vmva2mdba34vj2zotqw4twvcss3xeebuplciquhz6gi7h4pjljkq-lq",
    ("bob_smith", "cadc"): "skaha-h746gnq3mpn2w4foygxoosiwntv4ebzqwg7hhasycwiwmsznm3fq-lq",
    ("bob_cadc", "x"): "skaha-kkrsspspw5afliqloxdvzlw4wsf5vtsy4me3miaqrfimpduchioa-lq",
    ("bob-x", "y"): "bob-x-y-lq",
    ("bob", "x-y"): "bob-x-y-lq",
    ("cadc", "Bob"): "skaha-6br7oheyiwqtmy5qvbsos4b6xz7xaal4s443qhvdck7qivdsoggq-lq",
}

PAIRS = [
    ("bob", "cadc"),
    ("bob", "default"),
    ("Bob", "cadc"),
    ("cadc", "Bob"),
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


def local_queue_name(user: str, community: str) -> str:
    """Return the LocalQueue name for one exact user and community pair."""
    readable = f"{user}-{community}-lq"
    if len(readable) <= 63 and _DNS_1123_LABEL.fullmatch(readable):
        return readable
    payload = b"skaha-localqueue-v1\x00"
    for part in (user, community):
        encoded = part.encode()
        payload += struct.pack(">I", len(encoded)) + encoded
    digest = base64.b32encode(hashlib.sha256(payload).digest()).decode().rstrip("=").lower()
    return f"skaha-{digest}-lq"


def _expect(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def _check() -> None:
    for pair, expected in _KNOWN.items():
        actual = local_queue_name(*pair)
        _expect(actual == expected, f"{pair[0]}/{pair[1]}: {actual} != {expected}")
    _expect(
        local_queue_name("bob-x", "y") == local_queue_name("bob", "x-y") == "bob-x-y-lq",
        "hyphen collision",
    )
    _expect(local_queue_name("Ab", "c") != local_queue_name("A", "bc"), "length prefix")
    _expect(local_queue_name("Bob", "cadc") != local_queue_name("cadc", "Bob"), "pair order")
    _expect(local_queue_name("bob", "cadc") != local_queue_name("Bob", "cadc"), "case")
    boundary = "u" * 52
    _expect(len(f"{boundary}-default-lq") == 63, "63 boundary fixture")
    _expect(local_queue_name(boundary, "default") == f"{boundary}-default-lq", "63 boundary")
    _expect(local_queue_name("u" * 53, "default").startswith("skaha-"), "64 boundary")
    for user, community in PAIRS:
        name = local_queue_name(user, community)
        readable = f"{user}-{community}-lq"
        if len(readable) <= 63 and _DNS_1123_LABEL.fullmatch(readable):
            _expect(name == readable, f"readable {user}/{community}")
        else:
            _expect(_DIGEST_NAME.fullmatch(name) is not None, f"digest {user}/{community}: {name}")


def main() -> None:
    _check()
    vectors = [
        {"user": user, "community": community, "localQueue": local_queue_name(user, community)}
        for user, community in PAIRS
    ]
    _OUT.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
