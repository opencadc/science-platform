from metrics.quantity import parse_cpu_to_cores, parse_memory_to_gib


def test_parse_cpu_to_cores() -> None:
    assert parse_cpu_to_cores("500m") == 0.5
    assert parse_cpu_to_cores("2") == 2.0
    assert parse_cpu_to_cores(4) == 4.0
    assert parse_cpu_to_cores(None) == 0.0


def test_parse_memory_to_gib() -> None:
    assert parse_memory_to_gib("1024Mi") == 1.0
    assert parse_memory_to_gib("1Gi") == 1.0
    assert round(parse_memory_to_gib("1G"), 3) == 0.931
    assert parse_memory_to_gib(1024**3) == 1.0
    assert parse_memory_to_gib(None) == 0.0
