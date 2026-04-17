from __future__ import annotations

from metrics.main import run


def test_run_uses_env_settings(monkeypatch) -> None:
    captured = {}

    def fake_run(app, host, port, log_level):
        captured["host"] = host
        captured["port"] = port
        captured["log_level"] = log_level

    monkeypatch.setenv("METRICS_HOST", "127.0.0.1")
    monkeypatch.setenv("METRICS_PORT", "9000")
    monkeypatch.setattr("metrics.main.uvicorn.run", fake_run)

    run()

    assert captured == {"host": "127.0.0.1", "port": 9000, "log_level": "info"}
