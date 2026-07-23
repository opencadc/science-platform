# Changelog

## [0.1.6](https://github.com/opencadc/science-platform/compare/metrics-v0.1.5...metrics-v0.1.6) (2026-07-23)


### Documentation

* **metrics:** ignore AI adapter dirs and fix ADR/docs cleanup ([399416b](https://github.com/opencadc/science-platform/commit/399416b2b825ba3ea3190dd9d825f5843d1b6e59))
* **metrics:** retire harness and milestone plans in favor of ADRs ([9bc7d92](https://github.com/opencadc/science-platform/commit/9bc7d9296b9e598204d7b511517e642f84d7df04))
* **metrics:** retire harness and milestone plans in favor of ADRs ([0cf4955](https://github.com/opencadc/science-platform/commit/0cf49556c5364bc8f0cfd40c2859af4188120660))

## [0.1.5](https://github.com/opencadc/science-platform/compare/metrics-v0.1.4...metrics-v0.1.5) (2026-05-13)


### Bug Fixes

* **metrics:** removed dependence on requiring cohort info for platform metrics ([404e118](https://github.com/opencadc/science-platform/commit/404e1186484c013ea63f4550a0e78577bd2a64e4))

## [0.1.4](https://github.com/opencadc/science-platform/compare/metrics-v0.1.3...metrics-v0.1.4) (2026-04-27)


### Features

* **metrics:** add reserved kube provider stub ([0ea0e62](https://github.com/opencadc/science-platform/commit/0ea0e62d4143b307df39843e1fbfe66fa516fa3a))
* **metrics:** add runtime, registry, and YAML settings ([d1d7e73](https://github.com/opencadc/science-platform/commit/d1d7e73ceda2f157e8c45ca203bf68e8a1ed4728))
* **metrics:** extend provider base protocol ([25b23a5](https://github.com/opencadc/science-platform/commit/25b23a5ce137aba1fd7b117ff62dfefb5dd95be5))
* **metrics:** platform route depends on MetricsRuntime ([127a9e3](https://github.com/opencadc/science-platform/commit/127a9e3f5d676cc21f5f62352d824ed995196a63))


### Bug Fixes

* **cleanup:** general cleanup to remove not implemented things and also have better naming ([56aa40f](https://github.com/opencadc/science-platform/commit/56aa40f790502b1ce2879753279eac193469cf62))


### Documentation

* **metrics:** document TTL cache backends ([b3bab8b](https://github.com/opencadc/science-platform/commit/b3bab8b219735a02d009f53362b1ab3b35258797))
* **metrics:** instantiated architecture rework ([7a18a37](https://github.com/opencadc/science-platform/commit/7a18a37dca8f11ffdb862402acd1bf9b1ac9c155))
* **metrics:** refresh architecture for M4 runtime ([90992ff](https://github.com/opencadc/science-platform/commit/90992ff1230ed1dd45dfb8b2be76f57bccf15926))
* **metrics:** refresh milestone plans and M4–M5 roadmap ([a43ce74](https://github.com/opencadc/science-platform/commit/a43ce745bbaadde7bf3f0371be15f3effe8a39ca))
* **metrics:** update milestone process ([73cc583](https://github.com/opencadc/science-platform/commit/73cc5830619a74fc3f78dd68e6560744328d94d0))

## [0.1.3](https://github.com/opencadc/science-platform/compare/metrics-v0.1.2...metrics-v0.1.3) (2026-04-24)


### Features

* **harness:** v2 ([74b48ad](https://github.com/opencadc/science-platform/commit/74b48ad3e1e9b4c7237647e68582648c52f42cfa))
* **metrics:** add Helm chart, Minikube scripts, and CI updates ([4af37e3](https://github.com/opencadc/science-platform/commit/4af37e31bc98db5df73b809bea1e9dddd930de06))
* **metrics:** implement CI/CD workflows for Metrics API, including linting, testing, and release automation ([38432e0](https://github.com/opencadc/science-platform/commit/38432e020d2081e4e43f30ea80dcc856ac7e92b4))
* **metrics:** Kueue Helm RBAC, Minikube helpers, and CI ([5b18b8d](https://github.com/opencadc/science-platform/commit/5b18b8d4718fa2544f53e0789800b3a9011f4842))
* **metrics:** Kueue platform engine and HTTP cache headers ([b5f56a5](https://github.com/opencadc/science-platform/commit/b5f56a54713f92e3bc474110e24ab9e07f61869e))
* **metrics:** M1 compose stack, prerequisites, CI and docs ([de0226d](https://github.com/opencadc/science-platform/commit/de0226ddea97b48d972a06389c06c2d2c1d47ea9))
* **metrics:** M3 layered layout and platform source settings ([b027145](https://github.com/opencadc/science-platform/commit/b027145c811fe1e85df195f0803f9f2f09c67d66))
* **metrics:** minikube smoke, Skaffold, and platform metrics cleanup ([90bb692](https://github.com/opencadc/science-platform/commit/90bb6920ff4c60fced228b7453138382040cfdab))
* **metrics:** v0.1 scafolding for the metrics service ([38b42f5](https://github.com/opencadc/science-platform/commit/38b42f55499fefe66914da3ce23c73fe46203563))


### Bug Fixes

* **build:** optimizations for docker build and improvements to ignoring non-needed files ([d905d99](https://github.com/opencadc/science-platform/commit/d905d99ff28016c4020c0b8f8e9e4b9e1ad99061))
* **build:** system ([8ec83b0](https://github.com/opencadc/science-platform/commit/8ec83b02417a3d5f1e5329be957b0f187c3c3e22))
* **ci:** bound minikube smoke waits and image loads ([754b23c](https://github.com/opencadc/science-platform/commit/754b23ca824241ef76cc31372e620d6a0bad036d))
* **cursor:** wire harness hooks to preToolUse and sync AGENTS ([85ab01f](https://github.com/opencadc/science-platform/commit/85ab01f1a18732f50084e6076fb7029e185b0493))
* **metrics:** clean kind smoke images on teardown ([c84898c](https://github.com/opencadc/science-platform/commit/c84898cde39b37b5456b29b0b6e5b778b5110e5e))
* **metrics:** improve kind smoke workflow ([873011d](https://github.com/opencadc/science-platform/commit/873011d858800720df0b6117872382a598e0516c))
* **metrics:** refine Kueue startup and cache behavior ([9936728](https://github.com/opencadc/science-platform/commit/9936728cf9435e43a1b9ddb9741143e5fa506a2d))
* **metrics:** repair release CI configuration ([cebf241](https://github.com/opencadc/science-platform/commit/cebf2414079ff1856f4a71ed21fb2dc10b85f9e3))
* **platform:** allocated now sums only status.flavorsUsage.resources[].total. It no longer adds borrowed, because Kueue total already includes borrowed quota ([8d23a77](https://github.com/opencadc/science-platform/commit/8d23a77d6b0d978bcd107f1f77ae66bd4ec8c836))
* **smoke:** use skaffold --detect-minikube=false in CI path ([d2113b9](https://github.com/opencadc/science-platform/commit/d2113b9d69308ee9c1f75cc903bddd6fb616bbd9))
* **style:** lint ([24b9cb8](https://github.com/opencadc/science-platform/commit/24b9cb8c2f116e93a1f05de092cfb4dd826af905))
* **test:** changed test setup to use kind instead of minikube ([3ee8ece](https://github.com/opencadc/science-platform/commit/3ee8ece26c8a155020caa9582f710cc4fb544c44))


### Documentation

* **agents:** updated review policy ([da05a9e](https://github.com/opencadc/science-platform/commit/da05a9e77044cecbfbd27276c1c4b14a8a6c061b))
* **metrics:** align milestone plans with roadmap review ([662e387](https://github.com/opencadc/science-platform/commit/662e387b5fd158fa0581bc32106a35e712c3bf21))
* **metrics:** Kueue runbooks, M2 outcomes, and plan updates ([3fa66c1](https://github.com/opencadc/science-platform/commit/3fa66c11d0edf55cc95a7047b9f0e534a9b42273))
* **metrics:** planning docs ([aab6adb](https://github.com/opencadc/science-platform/commit/aab6adbaf57cac6945b28bc3121b99cd9ac40556))
* **metrics:** realign M3 roadmap and environment contracts ([dba906f](https://github.com/opencadc/science-platform/commit/dba906f754a28504ec2bec747ae8039996a0323f))
* **metrics:** update AGENTS learned preferences and facts ([ef54664](https://github.com/opencadc/science-platform/commit/ef54664d81e0a57cfd8f42c31bb6130d3e2aca65))
* **plans:** updated metric plans for initial release ([fcf6956](https://github.com/opencadc/science-platform/commit/fcf6956448f9e74c88898afeebdbdc0d2ffd46b6))
* **plans:** updated project setup / delivery foundation spec ([8dffe1f](https://github.com/opencadc/science-platform/commit/8dffe1fd75e8e63000df664f282737a9b50e199f))

## [0.1.2](https://github.com/opencadc/science-platform/compare/metric-v0.1.1...metric-v0.1.2) (2026-04-24)


### Bug Fixes

* **metrics:** clean kind smoke images on teardown ([c84898c](https://github.com/opencadc/science-platform/commit/c84898cde39b37b5456b29b0b6e5b778b5110e5e))
* **metrics:** improve kind smoke workflow ([873011d](https://github.com/opencadc/science-platform/commit/873011d858800720df0b6117872382a598e0516c))
* **metrics:** repair release CI configuration ([cebf241](https://github.com/opencadc/science-platform/commit/cebf2414079ff1856f4a71ed21fb2dc10b85f9e3))
* **platform:** allocated now sums only status.flavorsUsage.resources[].total. It no longer adds borrowed, because Kueue total already includes borrowed quota ([8d23a77](https://github.com/opencadc/science-platform/commit/8d23a77d6b0d978bcd107f1f77ae66bd4ec8c836))

## [0.1.1](https://github.com/opencadc/science-platform/compare/metric-v0.1.0...metric-v0.1.1) (2026-04-24)


### Bug Fixes

* **build:** optimizations for docker build and improvements to ignoring non-needed files ([d905d99](https://github.com/opencadc/science-platform/commit/d905d99ff28016c4020c0b8f8e9e4b9e1ad99061))

## 0.1.0 (2026-04-24)


### Features

* **harness:** v2 ([74b48ad](https://github.com/opencadc/science-platform/commit/74b48ad3e1e9b4c7237647e68582648c52f42cfa))
* **metrics:** add Helm chart, Minikube scripts, and CI updates ([4af37e3](https://github.com/opencadc/science-platform/commit/4af37e31bc98db5df73b809bea1e9dddd930de06))
* **metrics:** implement CI/CD workflows for Metrics API, including linting, testing, and release automation ([38432e0](https://github.com/opencadc/science-platform/commit/38432e020d2081e4e43f30ea80dcc856ac7e92b4))
* **metrics:** Kueue Helm RBAC, Minikube helpers, and CI ([5b18b8d](https://github.com/opencadc/science-platform/commit/5b18b8d4718fa2544f53e0789800b3a9011f4842))
* **metrics:** Kueue platform engine and HTTP cache headers ([b5f56a5](https://github.com/opencadc/science-platform/commit/b5f56a54713f92e3bc474110e24ab9e07f61869e))
* **metrics:** M1 compose stack, prerequisites, CI and docs ([de0226d](https://github.com/opencadc/science-platform/commit/de0226ddea97b48d972a06389c06c2d2c1d47ea9))
* **metrics:** M3 layered layout and platform source settings ([b027145](https://github.com/opencadc/science-platform/commit/b027145c811fe1e85df195f0803f9f2f09c67d66))
* **metrics:** minikube smoke, Skaffold, and platform metrics cleanup ([90bb692](https://github.com/opencadc/science-platform/commit/90bb6920ff4c60fced228b7453138382040cfdab))
* **metrics:** v0.1 scafolding for the metrics service ([38b42f5](https://github.com/opencadc/science-platform/commit/38b42f55499fefe66914da3ce23c73fe46203563))


### Bug Fixes

* **ci:** bound minikube smoke waits and image loads ([754b23c](https://github.com/opencadc/science-platform/commit/754b23ca824241ef76cc31372e620d6a0bad036d))
* **cursor:** wire harness hooks to preToolUse and sync AGENTS ([85ab01f](https://github.com/opencadc/science-platform/commit/85ab01f1a18732f50084e6076fb7029e185b0493))
* **metrics:** refine Kueue startup and cache behavior ([9936728](https://github.com/opencadc/science-platform/commit/9936728cf9435e43a1b9ddb9741143e5fa506a2d))
* **smoke:** use skaffold --detect-minikube=false in CI path ([d2113b9](https://github.com/opencadc/science-platform/commit/d2113b9d69308ee9c1f75cc903bddd6fb616bbd9))
* **style:** lint ([24b9cb8](https://github.com/opencadc/science-platform/commit/24b9cb8c2f116e93a1f05de092cfb4dd826af905))
* **test:** changed test setup to use kind instead of minikube ([3ee8ece](https://github.com/opencadc/science-platform/commit/3ee8ece26c8a155020caa9582f710cc4fb544c44))


### Documentation

* **agents:** updated review policy ([da05a9e](https://github.com/opencadc/science-platform/commit/da05a9e77044cecbfbd27276c1c4b14a8a6c061b))
* **metrics:** align milestone plans with roadmap review ([662e387](https://github.com/opencadc/science-platform/commit/662e387b5fd158fa0581bc32106a35e712c3bf21))
* **metrics:** Kueue runbooks, M2 outcomes, and plan updates ([3fa66c1](https://github.com/opencadc/science-platform/commit/3fa66c11d0edf55cc95a7047b9f0e534a9b42273))
* **metrics:** planning docs ([aab6adb](https://github.com/opencadc/science-platform/commit/aab6adbaf57cac6945b28bc3121b99cd9ac40556))
* **metrics:** realign M3 roadmap and environment contracts ([dba906f](https://github.com/opencadc/science-platform/commit/dba906f754a28504ec2bec747ae8039996a0323f))
* **metrics:** update AGENTS learned preferences and facts ([ef54664](https://github.com/opencadc/science-platform/commit/ef54664d81e0a57cfd8f42c31bb6130d3e2aca65))
* **plans:** updated metric plans for initial release ([fcf6956](https://github.com/opencadc/science-platform/commit/fcf6956448f9e74c88898afeebdbdc0d2ffd46b6))
* **plans:** updated project setup / delivery foundation spec ([8dffe1f](https://github.com/opencadc/science-platform/commit/8dffe1fd75e8e63000df664f282737a9b50e199f))
