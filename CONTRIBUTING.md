# Contributing to OpenCADC

OpenCADC is the GitHub Organization used by the Canadian Astronomy Data Centre (CADC) and the Canadian Advanced Network for Astronomical Research (CANFAR).

We welcome contributions at OpenCADC!  If you have a fork or clone of any of the software maintained here in the OpenCADC GitHub repository, please consider contributing any changes you make back to the origin repository here at OpenCADC.  This benefits all involved.  We will be sure to respond in a timely manner to any issues or pull requests created.

## Creating Issues

Create issues when you discover any sort of problem with OpenCADC software or want to propose a new feature. Ensure the bug was not already reported by searching on GH Issues first. Only if you're unable to find an open issue addressing the problem, open a new one. Be sure to include a title and clear description, as much detail as possible, and a code sample or an executable test case demonstrating the unexpected behavior.

Use the same mechanism for new proposed features providing a rationale for it. It is a good idea to wait for feedback debating the merits of the feature before starting to implement it.

If you end up working on a solution for the issue, it should be referenced in the associated Pull Request.

## Guidelines for Pull Requests

In OpenCADC, the publicly released code lives in the main (or master) branch of the GitHub repository.  Therefore, contributions (modifications, additions) to OpenCADC software must be done through Pull Requests (PR).  While the pull request is open, OpenCADC maintainers will correspond with you through the PR.  Generally, before the PR is merged to the main branch, repositories will require:
* A code review with approval
* Code style conventions to be met
* Automated tests to pass

### Java Development Guidelines

Java code is licenced under the Affero GPL version 3 (AGPL-3.0) and each file originally created by CADC staff includes a standard bilingual copyright notice with `(C) Government of Canada ...`; modifications to those files (e.g. bug fixes and minor enhancements) assume copyright assignment to the original holder. New modules (libaries or applications) created and contributed should be under the same license and may contain their own suitable `(C)` claim; copyright is assumed to be assigned (to GoC) if the standard bilingual notice is included. Copyright policy for new files contributed to existing modules: **TBD**.

#### Tools
We currently target Java 11 runtime (should be no problem with later versions); some build files specify minimum source compatibility of `11` and others are still at `1.8` (being updated gradually from downstream modules back to the core libraries).

All java modules use `gradle` as the build tool and each repository includes the gradle wrapper for use in github workflows; it can also be used by developers directly (or note that the first run installs a complete gradle system in `$HOME/.gradle/wrapper/dists` which can be used directly). This is currently fixed at gradle-6.8.3 so build file style has to work with this version (see CI below).

All java software modules use the standard `build.gradle` file and standard maven/gradle directory structure in order to minimise build file directives. The name of the module (directory) should match the name of the build product: library (jar file) and application (tar, war, docker image).

#### Dependencies
All java libraries are published to Maven Central under the <a href="https://repo.maven.apache.org/maven2/org/opencadc">org.opencadc</a> group ID. Build files include `mavenCentral` and `mavenLocal` repositories so developers can normally build against a combination of published libraries and their own versions of libraries that are installed locally (currently: `gradle install` will install a library into `$HOME/.m2/repository`). Other repostitories or mechanisms for including external java libs in the classpath are unlikely to be accepted.

Java dependencies should be carefully curated to include direct dependencies on java libs and only rely on transitive dependencies for indirect dependencies. Exception: for external libraries (e.g. log4j, xerces and jdom2 xml parsers, spring-jdbc) it is preferrable to use the version specified by the core `cadc-util` library and _not_ declare any dependency on these even though they are used directly by the code. This allows for easier version upgrade (e.g. for security issues) and consistent use. For other external dependencies introduced elsewhere, care should be taken to consider future update scenarios and try to minimise the pain necessary to carry them out. The dependency list in all build files should be curated to remove unused dependencies and sometimes even block transitive dependencies from the runtime classpath.

#### Code Style
Java coding style is specified and validated using `checkstyle` via the <a href="https://github.com/opencadc/core/tree/main/cadc-quality">cadc-quality</a> library. Each repository includes this library in the test classpath and configures checkstyle via the repository `opencadc.gradle` file. Developers can run checkstyle locally using `gradle checkstyleMain` (we currently do not enforce checkstyle on test code **TBD**) to make sure they comply and **should** use the checkstyle configuration from `cadc-quality` to configure their development environment (IDE) where possible.

This is currently checkstyle-8.2 -- pretty old -- and has some defiencies that need to be addressed. In addition, `gradle javadoc` must have no errors and ideally no warnings (technical debt: most older code has lots of warnings).

The current code style checking really only covers small scale syntax and not larger scale code organisation and design. General principles:
* clear code so comments are not needed or can be minimised
* judicious comments to capture tricky details
* judicious comments to clarify larger scale logic in complex methods
* constructor args for required values; final for immutable fields, prefer public fields when null-able
* polymorphism: only when necessary
* in general, older code can be found in the `ca.nrc.cadc` java package and newer code in `org.opencadc`; consider renaming the base package if a major refactoring or version update is being done, but this is otherwise just cosmetic
* TODO: improve checkstyle to avoid IDE-code-format-wars

#### Continuous Integration
Continuous Integration (CI) is used to validate pull requests in github (when PR is opened or new commits added). Reviewer(s) will usually not look at a PR when CI fails; it is the responsibility of the person who created the PR to fix CI issues or add a comment explaining it. For example, if a PR requires a new version of a library that has not been published yet, add a comment explaining that and then the reviewer can trigger the CI again once the library is published.

Github CI is not currently used to publish java libraries or docker images.

#### Publishing
Java libraries are published to Maven Central manually by a CADC staff member.

Docker images are published (and signed) to `images.opencadc.org`  manually by a CADC staff member.

### Python Development Guidelines
Related Python projects are grouped in repos, each repo containing a number of application projects: `cadctools`, `caom2tools`, `vostools`. A project of general interest might be published on `PyPI` (The Python Project Index).
The OpenCADC Python software is expected to be available and work with all the official active versions of Python (see https://devguide.python.org/versions/.)

#### Directory Structure
A project, let's say `myapp` has the following structure:
`myapp` root directory with the following files:
* `setup.py` - script used by `pip` command to run install the application
* `setup.cfg` - configuration file for `pip`. It contains the dependencies and the version of the application.
* `README.md` - description of the project
* `LICENCE` - the license file
* `tox.ini` - `tox` configuration file as required for projects of general interest, that are to be published to PyPI

Subdirectories:
* `myapp/myapp` - contains the Python source code and the unit test code. The directory can contain multiple subdirectories. Each directory might have a `tests` directory for the unit tests corresponding to the directory. `tests` directories might have `data` subdirectories containing the test data used in unit tests.

#### Tools
Required Python packages:
- `pytest` - used to run the unit tests
- `flake8` - used to check the conformance with the coding styles. The CADC coding styles are very similar to the default `flake8` styles.
- `tox` - used to set up virtual environments with different configurations and execute tests
- `virtualenv` - (optional) to create a custom virtual environment
- `coverage` - (optional) the coverage Python package is used to measure the code coverage achieved through the unit tests

The code should follow the standard [PEP8 Style Guide for Python Code](https://peps.python.org/pep-0008/). In particular, this includes using only 4 spaces for indentation, and never tabs.

#### Testing
To run all the available configuration:
```commandline
tox
```

To check the available `tox` targets:
```commandline
tox list
```

To run a `tox` target:
```commandline
tox -e py311
```

`tox` targets are created in the `.tox` subdirectory. To activate a specific virtual environment:
```commandline
source .tox/py310/bin/activate
```

Once the virtual environment has been activated, use `pip` to install required software, e.g:
```commandline
pip install -e ".[test]"
```
to install the test environment.

To run the unit tests from a virtual environment:
```commandline
pytest <package_name>
```

#### Continuous Integration/ Continuous Development (CI/CD)
In order for a contribution (PR) to be merged into the project branch, it needs to be reviewed and accepted by at least one of the package maintainers and pass the CI/CD pre-conditions. These are implemented as GH Actions and check the following:
- project `egg` builds successfully
- unit tests are successfully executed in all the supported configurations (Python versions)
- no code style errors are present
- test code coverage does not decrease

CADC staff can also run integration tests. These are not always accessible to external collaborators because they require internal Authentication/Authorization (A&A).

Once a patch has been merged into the default branch it can, depending on the urgency and its content, be published to `PyPI` immediately as an updated version or it can be bundled with other minor changes to be released later. This is at the discretion of the package maintainers.


### Web Browser Application Development Guidelines

Our web applications span many years and are made up of many common technologies such as vanilla JavaScript, jQuery, CSS, and HTML, but we've also, in recent years, adopted ReactJS in our [Science Portal](https://github.com/opencadc/science-portal) application.

Our Open Source web applications:
- [Storage User Interface](https://github.com/opencadc/storage-ui.git) ([Try it](https://www.canfar.net/storage/list)) - Single Page Application (SPA) on top of the VOSpace APIs.
- [Science Portal](https://github.com/opencadc/science-portal.git) ([Try it](https://www.canfar.net/science-portal) - authentication required) - Single Page Application (SPA) to manage interactive and batch sessions for the CANFAR Science Platform.
- [Data Citation](https://github.com/opencadc/data-citation.git) ([Try it](https://www.canfar.net/citation/) - authentication required) - Single Page Application (SPA) to manage Digital Object Identifiers (DOI) for research datasets archived with CANFAR.

#### Testing

GitHub actions typically handle most QA level tasks.  Gradle still manages our Open Source web application offering:

```bash
$ gradlew -i clean build test
```

Running this should always return:

<span style="color: green; font-weight: bold;">BUILD SUCCESSFUL</span>

Testing in the Browser is important as well.  We include `Dockerfile`s with the web applications that can be used to build and run the applications in your Kubernetes cluster, or [docker-compose](https://docs.docker.com/compose/).

#### Submitting changes

Send a GitHub Pull Request to the appropriate [Repository](#web-browser-application-development-guidelines).  Ensure a Git Message is submitted with commits, and keep them small to ensure an accurate Code Review.

#### Coding conventions

We optimize for readability, and while not fully enforced at build time, CheckStyle is used in reviews and ensuring consistent style in the Java code.  See the [cadc-quality](https://github.com/opencadc/core/tree/master/cadc-quality) OpenCADC library for more information.  You can add the [cadc_checkstyle.xml](https://raw.githubusercontent.com/opencadc/core/master/cadc-quality/src/main/resources/cadc_checkstyle.xml) to your IDE's CheckStyle configuration to check as you type.

##### JavaScript

- No semicolons at end of statement
- Be consistent with quotes (i.e. pick single or double, and stick with it, or be consistent with the rest of the file)
- Use vanilla JavaScript when available
- Don't Repeat Yourself (DRY)
- Indent with two spaces
- Please ALWAYS put spaces after list items and method parameters (`[1, 2, 3]`, **not** `[1,2,3]`), around operators (`x += 1`, **not** `x+=1`), and around hash arrows
- Use `const` and `let`; avoid `var` unless there's a good reason for it

## Image (Container) Repositories

### CADC and CANFAR System Images and Helm Charts

For CADC and CANFAR services, the artefact that is delivered for deployment is a container.  The latest releases of these containers, as well as their accompanying Helm charts (when applicable), are available at the CADC Harbor Image Registry, here:  https://images.opencadc.org.

### Science Platform User/Science Images

Images created by the user community and made available to the CANFAR Science Platform are available at https://images.canfar.net

## Links
* [CADC](https://www.cadc-ccda.hia-iha.nrc-cnrc.gc.ca)
* [OpenCADC Code of Conduct](CODE_OF_CONDUCT.md)
* [OpenCADC License](LICENSE)
* [CANFAR](https://www.canfar.net)
* [CANFAR User Documentation](https://www.opencadc.org/science-containers/)
