# Skaha (1.0.0)

*A container based science platform in CANFAR.*

[![CI: Linting](https://github.com/opencadc/science-platform/actions/workflows/ci.linting.yml/badge.svg)](https://github.com/opencadc/science-platform/actions/workflows/ci.linting.yml)
[![CI: Testing](https://github.com/opencadc/science-platform/actions/workflows/ci.testing.yml/badge.svg)](https://github.com/opencadc/science-platform/actions/workflows/ci.testing.yml)
[![CD: Edge Build](https://github.com/opencadc/science-platform/actions/workflows/cd.edge.build.yml/badge.svg)](https://github.com/opencadc/science-platform/actions/workflows/cd.edge.build.yml)
[![CD: Skaha Release Build](https://github.com/opencadc/science-platform/actions/workflows/cd.skaha.release.build.yml/badge.svg)](https://github.com/opencadc/science-platform/actions/workflows/cd.skaha.release.build.yml)
[![CD: Metrics Release Image](https://github.com/opencadc/science-platform/actions/workflows/cd.metrics.release.build.yml/badge.svg)](https://github.com/opencadc/science-platform/actions/workflows/cd.metrics.release.build.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/opencadc/science-platform/badge)](https://scorecard.dev/viewer/?uri=github.com/opencadc/science-platform)
## Table of Contents
- [Skaha](#skaha)
  - [Table of Contents](#table-of-contents)
  - [CANFAR Science Platform documentation](#canfar-science-platform-documentation)
    - [User documentation](#user-documentation)
    - [Deployer and administrator documentation](#deployer-and-administrator-documentation)
  - [Acknowledgements](#acknowledgements)
  - [Overview](#overview)
  - [Deployment](#deployment)
  - [System Components](#system-components)
    - [skaha](#skaha-1)
    - [metrics](#metrics)
  - [Session Containers](#session-containers)
  - [Software Containers](#software-containers)
  - [Dependencies](#dependencies)

## CANFAR Science Platform Documentation

> [!NOTE]
> [User Documentation & Guides for the CANFAR Science Platform](https://www.opencadc.org/canfar/latest/)

> [!TIP]
> Bookmark the docs site if you work in the platform regularly; release notes and user-facing changes are published there.

### Deployer Documentation

> [!WARNING]
> Installing or upgrading Skaha, Metrics, or related services affects live clusters. Read the deployment material and validate changes in a non-production environment first.

> [!CAUTION]
> Misconfigured identity (AC), registry (reg), or storage integrations can block all users—verify dependencies in [Dependencies](#dependencies) before changing production.

Operational and repository-specific material for people who deploy or operate the platform:

| Area | Where to look |
| --- | --- |
| Cluster Deployment & Helm Charts | [Deployment Guide](https://github.com/opencadc/deployments) |
| Skaha Service | [skaha/README.md](./skaha/README.md) |
| Metrics API | [metrics/README.md](./metrics/README.md) |
| Image Cache / Registry Helpers | [image-cache/README.md](./image-cache/README.md) |

## Acknowledgements
This project has been funded in part by:
- The ALMA Cycle 7 development study with support from the National Radio Astronomy Observatory and the North American ALMA Science Centre
- The CANARIE Research Software Program Competitive Funding Call 3: Research Software Platform Re-Use
- The National Research Council Canada

## System Components

Core platform services run as containers that can be scaled on a cluster of nodes to meet storage and processing demands. The sections below describe the main APIs.

### skaha

Skaha is a general purpose online platform for running science containers interactively. It provides the API for:
- Listing published container images that the calling user is allowed to run
- Creating skaha sessions from published container images
- Launching container images to be displayed in desktop sessions

The complete API of skaha can be viewed at the [Skaha Service API](https://ws-uv.canfar.net/skaha).

The CANFAR Science Platform, a web interface to skaha, is at [canfar.net](https://www.canfar.net).

### metrics

The **Metrics** service collects and serves CANFAR platform metrics through a versioned REST API (FastAPI, containerized). See the [metrics service documentation](./metrics/README.md) for routes, configuration, and local development.

## Session Containers

Session containers are HTML5/websocket applications that can run in skaha.  Currently this consists of Jupyter Labs, CARTA Visualization, and NoVNC desktops.  More information on session containers and how they can be used in skaha can be found in the [science-containers](https://github.com/opencadc/science-containers/blob/main/containers) github repository.

## Software Containers

These are some of the astronomy science containers that have been built for skaha.  They run as applications within skaha.  The graphical aspects of the containers are displayed in skaha-desktop by sending the DISPLAY to skaha-desktop.

More information on software containers can be found in the [science-containers](https://github.com/opencadc/science-containers/blob/main/containers) github repository.

## Dependencies

skaha relies on a number of other OpenCADC modules to operate.

* [registry (reg) web service](https://github.com/opencadc/reg) — A registry service reads the capabilities and locations of other web services used by skaha.
* [access control (ac) web service](https://github.com/opencadc/ac) — If the IdentityManager implementation is configured to use cadc-access-control-server for authentication, an operational AC web service must be running.
* [credential delegation (cdp) web service](https://github.com/opencadc/cdp) — The CDP service obtains users' delegated proxy certificates.
* [cavern](https://github.com/opencadc/vos/tree/master/cavern) — Skaha is complemented by having the cavern VOSpace implementation mounted as a shared POSIX file system. Cavern is a VOSpace implementation where both the data and metadata are based on the contents of a file system. If software containers have access to the cavern file system, the contents can be accessed and shared through the cavern web service.
* posix/sssd -- Containers in skaha are always run _as the user_ and with the users' group memberships.  If skaha-desktop and software-containers are run with a SSSD configuration that points to the same LDAP instance as used by ac, the names of those uids and gids can be resolved.
