# CHANGELOG for Skaha User Session API (0.3.6)

## 2024.01.12 (0.3.6)
- Desktop sessions have trusted API access to the Skaha service
- Better support for Access Tokens

## 2023.11.14 (0.3.0)
- Desktop sessions are still not complete, but have improved.
  - Fix to call menu building using Tokens
  - Fix Desktop and Desktop App launching to use Tokens for authenciated access back to Skaha
- Fix PosixPrincipal username if missing

## 2023.11.02 (0.2.17)
- Remove unnecessary call to POSIX Mapper for Group mapping (Bug - performance)
- Fix when POSIX Mapper includes large number of users and/or groups (Bug)
- Clean up of code
