from metrics import hello


def test_hello_returns_package_message() -> None:
    assert hello() == "Hello from metrics!"
