# CHANGELOG for Skaha User Session API (0.3.0)

## 2023.11.14 (0.3.0)
- Fix to call menu building using Tokens
- Fix Desktop and Desktop App launching to use Tokens for authenciated access back to Skaha
- Fix PosixPrincipal username if missing

## 2023.11.02 (0.2.17)
- Remove unnecessary call to POSIX Mapper for Group mapping (Bug - performance)
- Fix when POSIX Mapper includes large number of users and/or groups (Bug)
- Clean up of code