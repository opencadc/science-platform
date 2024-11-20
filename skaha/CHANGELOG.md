# Changelog

## [0.24.0](https://github.com/opencadc/science-platform/compare/skaha-v0.23.1...skaha-v0.24.0) (2024-11-20)


### Features

* add ability to specify registry credentials in header ([3a06b2a](https://github.com/opencadc/science-platform/commit/3a06b2af919698c588b162eecda1b8833fdc3361))
* **container:** added build-stages for skaha based on eclipse-temurin@jdk11 and cadc-tomcat:1.3 ([3b51e5a](https://github.com/opencadc/science-platform/commit/3b51e5ab3b98d90c608cf119ac6f1241bdd5275a))
* expose repository hosts through authenticated api call ([128c57a](https://github.com/opencadc/science-platform/commit/128c57a211fc09293e885f971631df5cf5e397af))
* **github-actions:** added testing and code coverage in ci ([4e207a5](https://github.com/opencadc/science-platform/commit/4e207a511bc89e3c34f4b12f1e434800b0a3b4b5))
* **gradle:** added doc generation plugins, added gradle.properties file to manage project settings ([ffb9e50](https://github.com/opencadc/science-platform/commit/ffb9e5088f4e6ce781f08bac1a826f0e00c0f106))
* **gradle:** added spotless linting and formatting checks for java, yaml and gradle files ([a20e7fc](https://github.com/opencadc/science-platform/commit/a20e7fc1d7595d0d6e77e658b42b38cf803646cc))
* start of adding image registry user information ([fd427ef](https://github.com/opencadc/science-platform/commit/fd427eff5010c677dd70d68f401cc54844f57e59))


### Bug Fixes

* allow different types of private images to accommodate the ui ([aacc3c0](https://github.com/opencadc/science-platform/commit/aacc3c00fd01d5e4143afe3b7d1b6e37c275a08e))
* **build:** restricted ci to only build for x86 platforms for now since cadc-tomcat does not have arm builds ([f554886](https://github.com/opencadc/science-platform/commit/f554886498f4e46d629488fcd3bffff196860f76))
* **build:** split release version into major,minor,patch ([97b2891](https://github.com/opencadc/science-platform/commit/97b28913cb2fe18598f9b885bf95c66986c84a8c))
* code review cleanup ([60e524b](https://github.com/opencadc/science-platform/commit/60e524be0e7202fe7de6082efdbe2ea9a87882ec))
* correct client certificate injection ([6f23434](https://github.com/opencadc/science-platform/commit/6f2343479083bc0acfaddf39ed4fb028fd0c14ab))
* first code pass at adding secret ([b7ff496](https://github.com/opencadc/science-platform/commit/b7ff4966812624be430bce811f18366e9a58e054))
* increase test coverage and small checks and cleanup ([6027f29](https://github.com/opencadc/science-platform/commit/6027f29ef4ccd12e2426bae5dd06d303f62e6d9e))
* many bug fixes and cleanup ([1352f6a](https://github.com/opencadc/science-platform/commit/1352f6a0292b3d1061ad888c8a150ea4519c6705))
* **opencadc.gradle:** fixed configurations needed for integration tests ([3488c58](https://github.com/opencadc/science-platform/commit/3488c58b67efa3b501f5c93f2e57ca001c994f73))
* proper subject when injecting cert ([1fbd8cc](https://github.com/opencadc/science-platform/commit/1fbd8cc3e68f091c07722e0b23822aba659f4217))
* **release-please:** removed test file version.yaml used for release-please testing ([a2db905](https://github.com/opencadc/science-platform/commit/a2db9054c8e6cef4b04d169e5bf0a050769c25fa))
* remove registry auth information for desktop app launching ([1162efb](https://github.com/opencadc/science-platform/commit/1162efb9fd6fbb65e5a8d510997f0286df7ab2d2))
* remove skaha image vulnerabilities ([b221f1a](https://github.com/opencadc/science-platform/commit/b221f1ac072f00db7298f45e7b95172b6b670678))
* remove unnecessary check ([d89c758](https://github.com/opencadc/science-platform/commit/d89c75896f2b3193298bc576b43b1b14e3ebf574))
* removed image vulnerability and reset version for next release ([dd480ae](https://github.com/opencadc/science-platform/commit/dd480ae1a8b377fe51eb790bbf4d64c3d5ceb0d4))
* review rework ([a84525f](https://github.com/opencadc/science-platform/commit/a84525f841a5dfdda0f567aa70a0e3147925cfb8))
* test fixes ([fd2d1a1](https://github.com/opencadc/science-platform/commit/fd2d1a121b492dd65584e3c647df808a71400669))
* use new gms library to obtain userinfo endpoint ([901cc40](https://github.com/opencadc/science-platform/commit/901cc407bae2674e19f227588f572c11f7a79667))
