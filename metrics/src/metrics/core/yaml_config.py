"""YAML file settings source for the metrics configuration tree."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml
from pydantic.fields import FieldInfo
from pydantic_settings import PydanticBaseSettingsSource

_DEFAULT_CONFIG_PATH = "/etc/canfar/metrics/config.yaml"


def _config_path() -> Path:
    return Path(
        os.environ.get("METRICS_CONFIG_FILE", _DEFAULT_CONFIG_PATH),
    ).expanduser()


def _require_file() -> bool:
    v = os.environ.get("METRICS_REQUIRE_CONFIG_FILE", "")
    return v.strip().lower() in ("1", "true", "yes", "on")


def _load_metrics_dict_from_file(path: Path) -> dict[str, Any]:
    """Load the ``metrics`` mapping from ``path`` when the file exists and is well-formed.

    A missing file returns ``{}`` unless :envvar:`METRICS_REQUIRE_CONFIG_FILE` is truthy.

    **Empty config:** whitespace-only files, an empty YAML stream, a lone ``null``/``~``
    document, or a top-level empty mapping ``{}`` are treated as "no file settings" and
    return ``{}`` without requiring a ``metrics`` key.

    **Non-empty documents:** if the parsed YAML is a non-empty top-level mapping, it must
    include a ``metrics`` key whose value is a mapping (empty ``metrics: {}`` is allowed).

    Args:
        path: Resolved configuration file path.

    Returns:
        The inner ``metrics`` mapping with ``None`` values removed.

    Raises:
        FileNotFoundError: If the file is missing and a config file is required.
        ValueError: If the YAML shape does not satisfy the contract above.
    """
    if not path.is_file():
        if _require_file():
            msg = f"METRICS_REQUIRE_CONFIG_FILE is set but {path} is missing"
            raise FileNotFoundError(msg)
        return {}
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        return {}
    data = yaml.safe_load(text)
    if data is None:
        return {}
    if not isinstance(data, dict):
        msg = (
            f"{path} must be a YAML mapping at the top level with a "
            "'metrics:' key (see metrics configuration docs)."
        )
        raise ValueError(msg)
    if not data:
        return {}
    if "metrics" not in data:
        keys_repr = repr(sorted(data))
        msg = (
            f"{path} must contain a top-level 'metrics:' mapping "
            f"(present document keys: {keys_repr})."
        )
        raise ValueError(msg)
    raw = data["metrics"]
    if raw is None:
        msg = f"{path} has 'metrics:' but it must be a mapping, not null."
        raise ValueError(msg)
    if not isinstance(raw, dict):
        msg = f"{path} has 'metrics:' but it must be a YAML mapping, got {type(raw).__name__}."
        raise ValueError(msg)
    return {key: value for key, value in raw.items() if value is not None}


class MetricsYamlSettingsSource(PydanticBaseSettingsSource):
    """Applies `metrics.*` from YAML; lower priority than `env` (see `Settings` source order)."""

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        """Satisfy the base API; this source returns a flat dict from :meth:`__call__`."""
        return None, field_name, False

    def __call__(self) -> dict[str, Any]:
        """Load the ``metrics`` key from the YAML file and return a flat settings dict."""
        path = _config_path()
        metrics = _load_metrics_dict_from_file(path)
        if not metrics:
            return {}
        return {key: value for key, value in metrics.items() if value is not None}
