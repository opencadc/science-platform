# Changelog

## [0.26.0](https://github.com/opencadc/science-platform/compare/v0.25.0...0.26.0) (2024-11-26)


### Features

* **dependabot:** added automated checks for github actions and docker configs ([98e8341](https://github.com/opencadc/science-platform/commit/98e8341050ad92292ddaddea289170d2bb39670e))
* **gha:** added a new action to check for commit msg compliance ([47b0419](https://github.com/opencadc/science-platform/commit/47b0419a5869bae3c35808c48ab245d656b0b87c))
* **pre-commit:** added base pre-commit config ([807e5f5](https://github.com/opencadc/science-platform/commit/807e5f56fede5513ec19faf25b5df019941da391))
* **pre-commit:** added pre-commit config and checks for the repo ([a522163](https://github.com/opencadc/science-platform/commit/a5221639b9f23173a18e2a414e367840f17650f5))


### Bug Fixes

* **build:** fixed the build to be referenced to the base of the repo rather than dir:skaha ([2d6b60a](https://github.com/opencadc/science-platform/commit/2d6b60a3e0cc1f1c13d60fbf38a61d6eee161060))
* **config:** update to the pre-commit config ([41776f5](https://github.com/opencadc/science-platform/commit/41776f56b07963a3126edc9f94144dfb825b83ec))
* **gha:** cosign signing is not only done on sha digest, rather than tags, e.g. latest, vX.X.X etc ([9cfc1f5](https://github.com/opencadc/science-platform/commit/9cfc1f5b06283357705c885bc780551aee82dc2c))
* **gha:** edge builds are now only triggered when pushed to main ([e1183bf](https://github.com/opencadc/science-platform/commit/e1183bf9ecb0c1a1d2e64331a33eb069c5640245))
* **gha:** fix for cosign action to to properly sign the digest ([b57b945](https://github.com/opencadc/science-platform/commit/b57b9451ac2dfee6dfd95e3d3bffc32c0c2fa322))
* **gha:** fixed for release build cosign ([2f2c79c](https://github.com/opencadc/science-platform/commit/2f2c79cc91b28796bf232bd70bb929c98ce2d237))
* **gha:** release steps are now only run after a push to main, and not on pr events ([c5863c3](https://github.com/opencadc/science-platform/commit/c5863c354d8bf67c58f0e634f4791508c9d2f19d))
* **gradle:** added javadoc req. to spotless and removed yaml and misc file checking ([d458b8d](https://github.com/opencadc/science-platform/commit/d458b8d84606cee38149be8741623cc0841bc43e))
* **pre-commit:** added checks for code quality (currently disabled), leaking of secrets/passwords and errors in shell scripts ([f782245](https://github.com/opencadc/science-platform/commit/f782245c87f1dcdb697aa68942b90a4b97a4079b))
* **release-please:** removed v from tags ([fa5db36](https://github.com/opencadc/science-platform/commit/fa5db3670e6425fd6fec9a0abec2700fbef11f88))
* **security:** added egress audit for all github actions ([25e624c](https://github.com/opencadc/science-platform/commit/25e624ca9c8ed1297d5cf2e92fb5bc22e6e43b90))
* **security:** pinned all mutable github action dependencies to sha ([2a7db65](https://github.com/opencadc/science-platform/commit/2a7db657b72760ddfab1597af2317ca661aa5f48))

## [0.25.0](https://github.com/opencadc/science-platform/compare/v0.24.1...v0.25.0) (2024-11-21)


### Features

* add ability to specify registry credentials in header ([3a06b2a](https://github.com/opencadc/science-platform/commit/3a06b2af919698c588b162eecda1b8833fdc3361))
* **ci-cd:** added prod builds for platform/skaha ([1a05a47](https://github.com/opencadc/science-platform/commit/1a05a4764de340661cd5d6481d9f25e05f7ab23f))
* **container:** added build-stages for skaha based on eclipse-temurin@jdk11 and cadc-tomcat:1.3 ([3b51e5a](https://github.com/opencadc/science-platform/commit/3b51e5ab3b98d90c608cf119ac6f1241bdd5275a))
* **cosign:** added cosign verification for container image ([2c39cc1](https://github.com/opencadc/science-platform/commit/2c39cc1ab2ba3b3c83b592920e5e36e1b343461e))
* expose repository hosts through authenticated api call ([128c57a](https://github.com/opencadc/science-platform/commit/128c57a211fc09293e885f971631df5cf5e397af))
* **github-actions:** added ci for linting code ([2a52365](https://github.com/opencadc/science-platform/commit/2a52365af3fab53fa98db7875482625d229f95a4))
* **github-actions:** added testing and code coverage in ci ([4e207a5](https://github.com/opencadc/science-platform/commit/4e207a511bc89e3c34f4b12f1e434800b0a3b4b5))
* **gradle:** added doc generation plugins, added gradle.properties file to manage project settings ([ffb9e50](https://github.com/opencadc/science-platform/commit/ffb9e5088f4e6ce781f08bac1a826f0e00c0f106))
* **gradle:** added spotless linting and formatting checks for java, yaml and gradle files ([a20e7fc](https://github.com/opencadc/science-platform/commit/a20e7fc1d7595d0d6e77e658b42b38cf803646cc))
* **ossf:** added openSSF Scorecard  ([d9f5b70](https://github.com/opencadc/science-platform/commit/d9f5b702cf0f4d3716b58c625f244582b2e7a336))
* **release:** added release please trigger and build for edge builds ([704993d](https://github.com/opencadc/science-platform/commit/704993d390641bde1744ed102c025f7fa9d4a621))
* **security:** added CodeQL workflow for Java ([555014b](https://github.com/opencadc/science-platform/commit/555014b662ac7f7908958d827434d5b084958a5c))
* start of adding image registry user information ([fd427ef](https://github.com/opencadc/science-platform/commit/fd427eff5010c677dd70d68f401cc54844f57e59))


### Bug Fixes

* allow different types of private images to accommodate the ui ([aacc3c0](https://github.com/opencadc/science-platform/commit/aacc3c00fd01d5e4143afe3b7d1b6e37c275a08e))
* **build:** restricted ci to only build for x86 platforms for now since cadc-tomcat does not have arm builds ([f554886](https://github.com/opencadc/science-platform/commit/f554886498f4e46d629488fcd3bffff196860f76))
* **build:** split release version into major,minor,patch ([97b2891](https://github.com/opencadc/science-platform/commit/97b28913cb2fe18598f9b885bf95c66986c84a8c))
* **build:** updated to fix attestations to harbor ([127f7b0](https://github.com/opencadc/science-platform/commit/127f7b0110668f762063ce52cce92606f81df20e))
* code review cleanup ([60e524b](https://github.com/opencadc/science-platform/commit/60e524be0e7202fe7de6082efdbe2ea9a87882ec))
* **codecov:** updated codecov action and added verbose logging ([54ed5ff](https://github.com/opencadc/science-platform/commit/54ed5ff370361f2b83a28655b976d94c4df7b9bc))
* correct client certificate injection ([6f23434](https://github.com/opencadc/science-platform/commit/6f2343479083bc0acfaddf39ed4fb028fd0c14ab))
* **cosign:** updated to use v2.4.1 ([07b5041](https://github.com/opencadc/science-platform/commit/07b50417a4e5df046ba87b1a7c0269cde9e8e21b))
* first code pass at adding secret ([b7ff496](https://github.com/opencadc/science-platform/commit/b7ff4966812624be430bce811f18366e9a58e054))
* **gha:** added debug for release action ([71fe530](https://github.com/opencadc/science-platform/commit/71fe53099bef727513c607d6d04595c6085a36aa))
* **gha:** enabled release build ([6c8dbee](https://github.com/opencadc/science-platform/commit/6c8dbee399f1acda1f203c5424895b47abb0256d))
* **gha:** fix for release-please action ([50a755c](https://github.com/opencadc/science-platform/commit/50a755cd7dceda3024a103648872bbbe5f247cdb))
* **gha:** release action debug ([85af615](https://github.com/opencadc/science-platform/commit/85af615bb9e14b0affff46b03555752310138074))
* **gha:** release please fix for monorepo packages ([2128c32](https://github.com/opencadc/science-platform/commit/2128c32a53c35140419d86122bb187fe5335e634))
* **gha:** release verification ([4235ab5](https://github.com/opencadc/science-platform/commit/4235ab56d53f68fb73bad772a7a57a2545bd009f))
* **gha:** syntax ([45d3cf8](https://github.com/opencadc/science-platform/commit/45d3cf840b5e501b2ef2449c3a65116a6fcd03d7))
* **gha:** typo ([06388c2](https://github.com/opencadc/science-platform/commit/06388c2f6af909e4ab607e11ce5025b94a6defc8))
* **github-actions:** added default read-only permissions for codeql ([01a1ddd](https://github.com/opencadc/science-platform/commit/01a1ddd38834a4a6a2e1dea023c7249e829f631d)), closes [#723](https://github.com/opencadc/science-platform/issues/723)
* **github-actions:** changed openssf scorecard to run daily at midnight ([51a03ca](https://github.com/opencadc/science-platform/commit/51a03ca89f105a3afa841180fbbc232021f2b689))
* **github-actions:** fix for CI:Testing upload artifacts ([514670e](https://github.com/opencadc/science-platform/commit/514670e3135d07785937b09a08b2b8c3a825281c))
* **github-actions:** fix for download artifact section ([e5dc399](https://github.com/opencadc/science-platform/commit/e5dc3994233bd714bff3eed2fefff61e48fc83e4))
* **github-actions:** fix for relative path for version files ([39b38f4](https://github.com/opencadc/science-platform/commit/39b38f48bf4eab3934857c35206d2650bf78fb41))
* **github-actions:** fix for release please action config to properly edit gradle.properties file ([b5afe92](https://github.com/opencadc/science-platform/commit/b5afe92211b20d5d7c958ed1472a0966b7982a07))
* **github-actions:** updated setup-java from v2 to v4.5.0 ([9e119ac](https://github.com/opencadc/science-platform/commit/9e119ac5f264e6c72ee72077fdb6fbad078f8277))
* increase test coverage and small checks and cleanup ([6027f29](https://github.com/opencadc/science-platform/commit/6027f29ef4ccd12e2426bae5dd06d303f62e6d9e))
* many bug fixes and cleanup ([1352f6a](https://github.com/opencadc/science-platform/commit/1352f6a0292b3d1061ad888c8a150ea4519c6705))
* **opencadc.gradle:** fixed configurations needed for integration tests ([3488c58](https://github.com/opencadc/science-platform/commit/3488c58b67efa3b501f5c93f2e57ca001c994f73))
* **openSSF:** fixed the scorecard action to properly use the updated version of upload-artifact in the ci ([c67cb9a](https://github.com/opencadc/science-platform/commit/c67cb9ae889d93937b5f8051bc646b40abbc4a23))
* proper subject when injecting cert ([1fbd8cc](https://github.com/opencadc/science-platform/commit/1fbd8cc3e68f091c07722e0b23822aba659f4217))
* **release-please:** added better permissions for the workflow action, fixed path for changelog ([b26b544](https://github.com/opencadc/science-platform/commit/b26b54449f454ae3e5eb6bf065cc11853f6d4e1c))
* **release-please:** changed for root package setup ([2eca6bb](https://github.com/opencadc/science-platform/commit/2eca6bbcd9b62b2e089ec08036e7278a923f28a4))
* **release-please:** changed manifest & config files ([01f82d5](https://github.com/opencadc/science-platform/commit/01f82d5490ff16f41f7cb5ef51a0ac99b8b73e58))
* **release-please:** changed skaha to be the root package, rather than a package in the monorepo ([4dc2081](https://github.com/opencadc/science-platform/commit/4dc2081d8dca4b630c7af903593a34542bea723c))
* **release-please:** config fix for base package ([1323304](https://github.com/opencadc/science-platform/commit/13233042ef4dc58e40040b9e41e92136bd7244a5))
* **release-please:** deprecated release please config in favor of manifest only ([8369646](https://github.com/opencadc/science-platform/commit/83696467db04351e7c5310f554ad8f527f6af7bd))
* **release-please:** fix for generic release on a yaml file ([0a542ea](https://github.com/opencadc/science-platform/commit/0a542ea5950814753a09fd8ac46391f6f041746f))
* **release-please:** fix for manifest file location and edge trigger build requies release please action to succeed ([f13fdd8](https://github.com/opencadc/science-platform/commit/f13fdd8ac0f39a2ee00471826964b9334dee2376))
* **release-please:** removed test file version.yaml used for release-please testing ([a2db905](https://github.com/opencadc/science-platform/commit/a2db9054c8e6cef4b04d169e5bf0a050769c25fa))
* remove registry auth information for desktop app launching ([1162efb](https://github.com/opencadc/science-platform/commit/1162efb9fd6fbb65e5a8d510997f0286df7ab2d2))
* remove skaha image vulnerabilities ([b221f1a](https://github.com/opencadc/science-platform/commit/b221f1ac072f00db7298f45e7b95172b6b670678))
* remove unnecessary check ([d89c758](https://github.com/opencadc/science-platform/commit/d89c75896f2b3193298bc576b43b1b14e3ebf574))
* removed image vulnerability and reset version for next release ([dd480ae](https://github.com/opencadc/science-platform/commit/dd480ae1a8b377fe51eb790bbf4d64c3d5ceb0d4))
* review rework ([a84525f](https://github.com/opencadc/science-platform/commit/a84525f841a5dfdda0f567aa70a0e3147925cfb8))
* test fixes ([fd2d1a1](https://github.com/opencadc/science-platform/commit/fd2d1a121b492dd65584e3c647df808a71400669))
* use new gms library to obtain userinfo endpoint ([901cc40](https://github.com/opencadc/science-platform/commit/901cc407bae2674e19f227588f572c11f7a79667))
