# skaha - A Container-based Science Platform in CANFAR

## Science Portal Documentation

The CANFAR Science Portal can be found here:  [CANFAR Science Portal](https://www.canfar.net)

A Canadian Astronomy Data Centre (CADC) Account and authorization to use the portal are required first.

To request a CADC Account:  http://www.cadc-ccda.hia-iha.nrc-cnrc.gc.ca/en/auth/request.html

To request authorization to use the science portal, send an email to [support@canfar.net](mailto:support@canfar.net)

## Community and Support

Dicussions of issues and platform features take place in the Science Platform Slack Channel:  [Science Platform Slack Channel](https://cadc.slack.com/archives/C01K60U5Q87)

Reporting of bugs and new feature requests can also be made as github issues:  https://github.com/opencadc/skaha/issues

Contributions to the platform (including updates or corrections to the documentation) can be submitted as pull requests to this github project.

General inquiries can be made to [support@canfar.net](mailto:support@canfar.net)

## Interactive Sessions

The CANFAR Science Portal allows users to run both pre-built, shared containers or private, custom containers.  Users publish container images to the CANFAR Image Registry at https://images.canfar.net

Logins to the Image Registry are done through the OpenID Connect Login button using your CADC/CANFAR crendentials.  Contact the project image coordinator to obtain authorization to publish images to that project.

Details of image publishing for use in the CANFAR science portal can be found here:  [Publishing](https://github.com/opencadc/skaha/tree/master/containers)

### Jupyter Notebooks

The CANFAR Science Portal allows for the creation of Jupyter Notebooks.  [Jupyter](https://jupyter.org/)

### CARTA 

The CANFAR Science Portal allows for the creation of CARTA (Cube Analysis and Rendering Tool for Astronomy) sessions.  [CARTA](https://cartavis.org/) 

### ARCADE Desktop

- ARCADE documentation and tutorials: [ARCADE](https://github.com/canfar/arcade)
- Launching a CASA window in ARCADE YouTube tutorial:  [YouTube Tutorial](https://youtu.be/GDDQ3jKbldU)

## Storage

All sessions and applications that run in the Science Portal have filesystem access to the same storage, mounted at `/arc`.  Within are `/arc/home` (contains all home directories) and `/arc/projects` (for project use).  We encourage the use of `/arc/projects` for most data, and `/arc/home` for personalized configuration.

`arc` is accesible through an API based on the IVOA VOSpace specification.  The following list the ways it can be accessed:
- Through the `/arc` filesystem mount on all portal sessions and ARCADE windows.
- Using the storage management UI in CANFAR: https://www.canfar.net/storage/arc/list
- Using the [CADC Python libraries](https://github.com/opencadc/vostools/tree/master/vos)
- Using sshfs [documentation](https://github.com/canfar/arcade/tree/master/arcade-tutorial)

Please take care to protect sensitive information by ensuring it is not publicly accessible.

## Programmatic Access

The skaha API definition and science platform service are here:  https://ws-uv.canfar.net/skaha

### Authentication

All requests to the skaha API must be made with CADC/CANFAR credentials.  In the science portal the credentials are handled with cookies, but for programatic access, either x.509 client certificates or authorization tokens must be used.

#### Authorization Tokens

Tokens can be obtained from the CANFAR Access Control service by providing your CADC username and password over a secure SSL connection:

```curl https://ws-cadc.canfar.net/ac/login -d "username=<username>" -d "password=<password>"```

The token returned can then be used for making authenticated requests to skaha.  For example:

```curl -H "Authorization: Bearer <token>" https://ws-uv.canfar.net/skaha/session```

Tokens are valid for 48 hours.

#### Proxy Certificates

Another way to authenticate to the skaha API is by using proxy certificates.  Using the [CADC Python libraries](https://github.com/opencadc/vostools/tree/master/vos), the `cadc-get-cert` tool will download a proxy certificate to the default location: `$HOME/.ssl/cadcproxy.pem`.

```cadc-get-cert -u <username>```

By default the proxy certificate is valid for 10 days.  This can be modified (to a maximum of 30 days) with the `--days-valid` parameter.

Instead of prompting for your password, cadc-get-cert can read it from your `$HOME/.netrc` file using the `--netrc-file` parameter.

#### Headless Jobs


![canfar](canfar-logo.png)
