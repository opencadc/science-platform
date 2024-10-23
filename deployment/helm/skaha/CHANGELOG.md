# CHANGELOG for Skaha User Session API (Chart 0.9.0)

## 2024.10.23 (0.9.0)
- Add `x-skaha-registry-auth` request header support to set Harbor CLI secret (or other Image Registry secret)

## 2024.10.18 (0.8.0)
- Allow setting nodeAffinity values for proper scheduling.

## 2024.10.10 (0.7.8)
- Fix for client certificate injection

## 2024.10.07 (0.7.3)
- Fix for security context in image caching job

## 2024.10.04 (0.7.2)
- Fix to inject user client certificates properly

## 2024.10.03 (0.7.1)
- Small fix to ensure userinfo endpoint is obtained from the Identity Provider for applications using the StandardIdentityManager

## 2024.09.20 (0.6.0)
- Feature to allow mounting volumes into user sessions

## 2024.09.19 (0.5.1)
- Fix to add `headlessPriorityGroup` and `headlessPriorityClass` configurations

## 2024.09.10
- Enforce configuration by deployers by removing some default values
- Sessions now contain their own stanza (`sessions:`)
  - `deployment.skaha.maxUserSessions` is now `deployment.skaha.sessions.maxCount`
  - `deployment.skaha.sessionExpiry` is now `deployment.skaha.sessions.expirySeconds`
  - Added `deployment.skaha.sessions.minEphemeralStorage` and `deployment.skaha.sessions.maxEphemeralStorage`

## 2024.09.04
- Fix for Desktop Applications not starting due to API token being overwritten

## 2024.05.06
- Small change to deploy on CADC infrastructure with CephFS quotas

## 2024.03.11
- Large development branch merged into `master`.  This is a point release only.

## 2024.02.26
- Fix multiple users in Desktop session Applications
- Add `loggingGroup` access to permit log level modification
- Externalize the CARTA startup script to better diagnose issues
- Bug fixes around user home directory allocations

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
