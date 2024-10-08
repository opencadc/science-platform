# CHANGELOG for Cavern User Storage (Chart 0.5.0)

## 2024.10.08
- Update to omit PostgreSQL in favour of an existing database.

## 2024.10.03 (0.4.7)
- Small bug fix to properly ask the OIDC Discovery document for the userinfo endpoint.

## 2024.09.18 (0.4.6)
- Bug fix for setting quotas

## 2024.09.13 (0.4.5)
- Bug fix for database initialization

## 2024.05.27 (0.4.0)
- Enforcing some values to be set by the deployer.
- Fix for Quota reporting
- Fix for folder size reporting
- Added `extraHosts` mapping for manual DNS entries
- Added `extraConfigData` to add to the Cavern `ConfigMap`.

## 2024.03.12 (0.3.0)
- Bug fixes in allocation
- Bug fixes in read permission with sub-paths

## 2023.11.29 (0.2.0)
- Fix to support creating links in the User Interface properly
- Support for pre-authorized URL key generation
- Added `applicationName` configuration to rename the underlying WAR file to serve from another endpoint
- Code cleanup
