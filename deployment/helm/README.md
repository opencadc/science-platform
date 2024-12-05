# Deployment Guide

- [Dependencies](#dependencies)
- [Helm](#helm-repository)
- [Quick Start](#quick-start)
  - [Base install](#base-install)
  - [Persistent Volumes](#persistent-volumes-and-persistent-volume-claims)
  - [POSIX Mapper install](#posix-mapper-install)
  - [Skaha install](#skaha-install)
  - [Science Portal install](#science-portal-user-interface-install)
  - [Cavern install](#cavern-user-storage-api-install)
  - [Storage User Interface install](#user-storage-ui-installation)
- [Obtaining a bearer token](#obtaining-a-bearer-token)
- [Flow](#flow)
- [Structure](#structure)

## Dependencies

- An existing Kubernetes cluster.
- An [Service Registry deployment](https://github.com/opencadc/reg/tree/master/reg)

## Quick Start

```bash
helm repo add science-platform https://images.opencadc.org/chartrepo/platform
helm repo add science-platform-client https://images.opencadc.org/chartrepo/client
helm repo update

helm install --values my-base-local-values-file.yaml base science-platform/base
helm install -n skaha-system --values my-posix-mapper-local-values-file.yaml posixmapper science-platform/posixmapper
helm install -n skaha-system --values my-skaha-local-values-file.yaml skaha science-platform/skaha
helm install -n skaha-system --dependency-update --values my-scienceportal-local-values-file.yaml scienceportal science-platform/scienceportal
helm install -n skaha-system --values my-cavern-local-values-file.yaml cavern science-platform/cavern
helm install -n skaha-system --dependency-update --values my-storage-ui-local-values-file.yaml storage-ui science-platform-client/storageui
```

More details below.

### Helm repository

Add the Helm repository:
```bash
helm repo add science-platform https://images.opencadc.org/chartrepo/platform
helm repo update
```

### Base install

The [Base](base) install will create ServiceAccount, Role, Namespace, and RBAC objects needed to place the Skaha service.

Create a `my-base-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](base/values.yaml).  Mainly the
Traefik Default Server certificate (optional if needed):

`my-base-local-values-file.yaml`
```yaml
secrets:
    default-certificate:
        tls.crt: <base64 encoded server certificate>
        tls.key: <base64 encoded server key>
```

```bash
helm install --values my-base-local-values-file.yaml base science-platform/base

NAME: base
LAST DEPLOYED: Thu Sep 28 07:28:45 2023
NAMESPACE: default
STATUS: deployed
REVISION: 1
```

### Persistent Volumes and Persistent Volume Claims

**Note** 
The `base` MUST be installed first as it creates the necessary Namespaces for the Persistent Volume Claims!

**Important**
There are two (2) Persistent Volume Claims that are used in the system, due to the fact that there are two (2) Namespaces (`skaha-system` and `skaha-workload`).  These PVCs, while
having potentially different configurations, **SHOULD** point to the same storage.  For example, if two `hostPath` PVCs are created, the `hostPath.path` **MUST** point to the same
folder in order to have shared content between the Services (`skaha`, `cavern`) and the User Sessions (Notebooks, CARTA, etc.).

It is expected that the deployer, or an Administrator, will create the necessary Persistent Volumes (if needed), and the required Persistent Volume Claims at
this point.  There are sample [Local Storage](https://kubernetes.io/docs/concepts/storage/volumes/#local) Persistent Volume examples in the `base/volumes` folder.


#### Required Persistent Volume Claim

It is expected that there is a Persistent Volume Claim with the name of the Skaha Workload namespace hyphenated with `cavern-pvc`.  This will provide the
backing storage to the User Sessions.  Using the default values, this means:

`skaha-workload-cavern-pvc`

will exist as a Persistent Volume Claim in the `skaha-workload` namespace.


### POSIX Mapper install

The [POSIX Mapper Service](posix-mapper) is required to provide a UID to Username mapping, and a GID to Group Name mapping so that any Terminal access properly showed System Users in User Sessions.  It will generate UIDs when a user is requested, or a GID when a Group is requested, and then keep track of them.

This service is required to be installed _before_ the Skaha service.

Create a `my-posix-mapper-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](posix-mapper/values.yaml).

`my-posix-mapper-local-values-file.yaml`
```yaml
# POSIX Mapper web service deployment
deployment:
  hostname: example.org
  posixMapper:
    # Optionally set the DEBUG port.
    # extraEnv:
    # - name: CATALINA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    # - name: JAVA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"

    # Uncomment to debug.  Requires options above as well as service port exposure below.
    # extraPorts:
    # - containerPort: 5555
    #   protocol: TCP

    # Resources provided to the Skaha service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Used to set the minimum UID.  Useful to avoid conflicts.
    minUID: 10000

    # Used to set the minimum GID.  Keep this much higher than the minUID so that default Groups can be set for new users.
    minGID: 900000

    # The URL of the IVOA Registry
    registryURL: https://example.org/reg

    # Optionally mount a custom CA certificate
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: posix-manager-cacert-secret

  # Specify extra hostnames that will be added to the Pod's /etc/hosts file.  Note that this is in the
  # deployment object, not the posixMapper one.
  #
  # These entries get added as hostAliases entries to the Deployment.
  #
  # Example:
  # extraHosts:
  #   - ip: 127.3.34.5
  #     hostname: myhost.example.org
  #
  # extraHosts: []

# Declare the storage for the skaha service to use.
storage:
  service:
    spec:
      persistentVolumeClaim:
        claimName: skaha-pvc # Match this label up with whatever was installed in the base install, or the desired PVC, or create dynamically provisioned storage.

secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
#   posix-manager-cacert-secret:
#     ca.crt: <base64 encoded ca crt>

# These values are preset in the catalina.properties, and this default database only exists beside this service.
# It's usually safe to leave these as-is.
# postgresql:
#   auth:
#     username: posixmapper
#     password: posixmapperpwd
#     database: mapping
#     schema: mapping
#   storage:
#     spec:
#       hostPath:
#         path: "/posixmapper/data"

# An omission equals true, so set this explicitly to false as we already installed it.
base:
  install: false
```

It is recommended to install into the `skaha-system` namespace, but not required.
```bash
helm install -n skaha-system  --values my-posix-mapper-local-values-file.yaml posixmapper science-platform/posixmapper

NAME: posixmapper
LAST DEPLOYED: Thu Sep 28 07:28:45 2023
NAMESPACE: skaha-system
STATUS: deployed
REVISION: 1
```

Test it.
```bash
# See below for tokens
export SKA_TOKEN=...
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" https://example.host.com/posix-mapper/uid

[]%

curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" "https://example.host.com/posix-mapper/uid?user=mynewuser"

mynewuser:x:1000:1000:::
```

### Skaha install

The Skaha service will manage User Sessions.  It relies on the POSIX Mapper being deployed, and available to be found
via the IVOA Registry:

`/reg/resource-caps`
```
...
# Ensure the hostname matches the deployment hostname.
ivo://example.org/posix-mapper = https://example.host.com/posix-mapper/capabilities
...
```

Create a `my-skaha-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](skaha/values.yaml).

`my-skaha-local-values-file.yaml`
```yaml
# Skaha web service deployment
deployment:
  hostname: example.org
  skaha:
    # Optionally set the DEBUG port.
    # extraEnv:
    # - name: CATALINA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    # - name: JAVA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"

    # Uncomment to debug.  Requires options above as well as service port exposure below.
    # extraPorts:
    # - containerPort: 5555
    #   protocol: TCP

    # Set the top-level-directory name that gets mounted at the root.
    # skahaTld: "/cavern"

    defaultQuotaGB: "10"

    # Space delimited list of allowed Image Registry hosts.  These hosts should match the hosts in the User Session images.
    registryHosts: "images.canfar.net"

    # The IVOA GMS Group URI to verify users against for permission to use the Science Platform.
    # See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2
    usersGroup: "ivo://example.org/gms?skaha-platform-users"

    # The IVOA GMS Group URI to verify images without contacting Harbor.
    # See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2
    adminsGroup: "ivo://example.org/gms?skaha-admin-users"

    # The IVOA GMS Group URI to verify users against for permission to run headless jobs.
    # See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2
    headlessGroup: "ivo://example.org/gms?skaha-headless-users"

    # The IVOA GMS Group URI to verify users against that have priority for their headless jobs.
    # See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2
    headlessPriorityGroup: "ivo://example.org/gms?skaha-priority-headless-users"

    # Class name to set for priority headless jobs.
    headlessPriorityClass: "uber-user-vip"

    # Array of GMS Group URIs allowed to set the logging level.  If none set, then nobody can change the log level.
    # See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 for GMS Group URIs
    # See https://github.com/opencadc/core/tree/main/cadc-log for Logging control
    loggingGroups:
      - "ivo://example.org/gms?skaha-logging-admin-users"

    # The Resource ID (URI) of the Service that contains the Posix Mapping information
    posixMapperResourceID: "ivo://example.org/posix-mapper"

    # URI or URL of the OIDC (IAM) server.  Used to validate incoming tokens.
    oidcURI: https://iam.example.org/

    # The Resource ID (URI) of the GMS Service.
    gmsID: ivo://example.org/gms

    # The absolute URL of the IVOA Registry where services are registered
    registryURL: https://example.org/reg

    # Optionally describe how this Pod will be scheduled using the nodeAffinity clause. This applies to Skaha itself.
    # Note the different indentation level compared to the sessions.nodeAffinity.
    # See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/
    # See the [Sample Skaha Values file](skaha/sample-local-values.yaml).
    # Example:
    nodeAffinity:
      # Only allow Skaha to run on specific Nodes.
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: kubernetes.io/hostname
            operator: In
            values:
            - my-special-node-host

    # Settings for User Sessions.  Sensible defaults supplied, but can be overridden.
    # For units of storage, see https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#meaning-of-memory.
    sessions:
      expirySeconds: "345600"   # Duration, in seconds, until they expire and are shut down.
      maxCount: "3"  # Max number of sessions per user.
      minEphemeralStorage: "20Gi"   # The initial requested amount of ephemeral (local) storage.  Does NOT apply to Desktop sessions.
      maxEphemeralStorage: "200Gi"  # The maximum amount of ephemeral (local) storage to allow a Session to extend to.  Does NOT apply to Desktop sessions.

      # When set to 'true' this flag will enable GPU node scheduling.  Don't forget to declare any related GPU configurations, if appropriate, in the nodeAffinity below!
      # gpuEnabled: false

      # Set the YAML that will go into the "affinity.nodeAffinity" stanza for Pod Spec in User Sessions.  This can be used to enable GPU scheduling, for example, 
      # or to control how and where User Session Pods are scheduled.  This can be potentially dangerous unless you know what you are doing.
      # See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity
      # nodeAffinity: {}

      # Mount CVMFS from the Node's mounted path into all User Sessions.
      extraVolumes:
      - name: cvmfs-mount
        volume:
          type: HOST_PATH     # HOST_PATH is for host path
          hostPath: "/cvmfs"  # Path on the Node to look for a source folder
          hostPathType: Directory
        volumeMount:
          mountPath: "/cvmfs"   # Path to mount on the User Sesssion Pod.
          readOnly: false
          mountPropagation: HostToContainer

    # Optionally mount a custom CA certificate as an extra mount in Skaha (*not* user sessions)
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: skaha-cacert-secret

    # Other data to be included in the main ConfigMap of this deployment.
    # Of note, files that end in .key are special and base64 decoded.
    # 
    # extraConfigData:
    
    # Resources provided to the Skaha service.
    # For units of storage, see https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#meaning-of-memory.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

  # Specify extra hostnames that will be added to the Pod's /etc/hosts file.  Note that this is in the
  # deployment object, not the skaha one.
  #
  # These entries get added as hostAliases entries to the Deployment.
  #
  # Example:
  # extraHosts:
  #   - ip: 127.3.34.5
  #     hostname: myhost.example.org
  #
  # extraHosts: []

# Set these labels appropriately to match your Persistent Volume labels.
# The storage.service.spec can be anything that supports ACLs, such as CephFS or Local.
# The CephFS Volume can be dynamically allocated here for the storage.service.spec:
# Example:
# storage:
#   service:
#     spec:
#       cephfs:
#         mons:
#           ...
# Default is a PersistentVolumeClaim to the Local Storage.
storage:
  service:
    spec:
      persistentVolumeClaim:
        claimName: skaha-pvc # Match this label up with whatever was installed in the base install, or the desired PVC, or create dynamically provisioned storage.

secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
#   skaha-cacert-secret:
#     ca.crt: <base64 encoded ca crt>
```

It is recommended to install into the `skaha-system` namespace, but not required.
```bash
helm install -n skaha-system --values my-skaha-local-values-file.yaml skaha science-platform/skaha

NAME: skaha
LAST DEPLOYED: Thu Sep 28 07:31:10 2023
NAMESPACE: skaha-system
STATUS: deployed
REVISION: 1
```

Test it.
```bash
# See below for tokens
export SKA_TOKEN=...
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" https://example.host.com/skaha/v0/session

[]%

# xxxxxx is the returned session ID.
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" -d "ram=1" -d "cores=1" -d "image=images.canfar.net/canucs/canucs:1.2.5" -d "name=myjupyternotebook" "https://example.host.com/skaha/v0/session"
```

### Science Portal User Interface install

The Science Portal service will manage User Sessions.  It relies on the Skaha service being deployed, and available to be found
via the IVOA Registry:

`/reg/resource-caps`
```
...
# Ensure the hostname matches the deployment hostname.
ivo://example.org/skaha = https://example.host.com/skaha/capabilities
...
```

Create a `my-science-portal-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](science-portal/values.yaml).

`my-science-portal-local-values-file.yaml`
```yaml
deployment:
  hostname: example.org
  sciencePortal:
    # The Resource ID of the Service that contains the URL of the Skaha service in the IVOA Registry
    skahaResourceID: ivo://example.org/skaha

    # OIDC (IAM) server configuration.  These are required
    # oidc:
    #
    # Location of the OpenID Provider (OIdP), and where users will login
    #   uri: https://iam.example.org/

      # The Client ID as listed on the OIdP.  Create one at the uri above.
    #   clientID: my-client-id

      # The Client Secret, which should be generated by the OIdP.
    #   clientSecret: my-client-secret

      # Where the OIdP should send the User after successful authentication.  This is also known as the redirect_uri in OpenID.
    #   redirectURI: https://example.com/science-portal/oidc-callback

      # Where to redirect to after the redirectURI callback has completed.  This will almost always be the URL to the /science-portal main page (https://example.com/science-portal).
    #   callbackURI: https://example.com/science-portal/

      # The standard OpenID scopes for token requests.  This is required, and if using the SKAO IAM, can be left as-is.
    #   scope: "openid profile offline_access"

    # Optionally mount a custom CA certificate
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: science-portal-cacert-secret

    # The theme name for styling.
    # src: The SRCNet theme
    # canfar: The CANFAR theme for internal CADC deployment
    # themeName: {src | canfar}

    # Labels on the tabs
    # Default:
    # tabLabels:
    #  - Public
    #  - Advanced
    # tabLabels: []

    # Other data to be included in the main ConfigMap of this deployment.
    # Of note, files that end in .key are special and base64 decoded.
    # 
    # extraConfigData:
    
    # Resources provided to the Science Portal service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Optionally describe how this Pod will be scheduled using the nodeAffinity clause. This applies to Science Portal itself.
    # See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/
    # Example:
    nodeAffinity:
      # Only allow Science Portal to run on specific Nodes.
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: kubernetes.io/hostname
            operator: In
            values:
            - my-special-ui-host

  # Specify extra hostnames that will be added to the Pod's /etc/hosts file.  Note that this is in the
  # deployment object, not the sciencePortal one.
  #
  # These entries get added as hostAliases entries to the Deployment.
  #
  # Example:
  # extraHosts:
  #   - ip: 127.3.34.5
  #     hostname: myhost.example.org
  #
  # extraHosts: []

# secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
  # science-portal-cacert-secret:
    # ca.crt: <base64 encoded ca.crt blob>
```

### Cavern (User Storage API) install

The Cavern API provides access to the User Storage which is shared between Skaha and all of the User Sessions.  A [Bearer token](#obtaining-a-bearer-token) is required when trying to read
private access, or any writing.

Create a `my-cavern-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](cavern/values.yaml).

`my-cavern-local-values-file.yaml`
```yaml
# Cavern web service deployment
deployment:
  hostname: example.org
  cavern:
    # How cavern identifies itself.  Required.
    resourceID: "ivo://example.org/cavern"

    # Set the Registry URL pointing to the desired registry.  Required
    registryURL: "https://example.org/reg"

    # How to find the POSIX Mapper API.  URI (ivo://) or URL (https://).  Required.
    posixMapperResourceID: "ivo://example.org/posix-mapper"

    filesystem:
      # persistent data directory in container
      dataDir: # e.g. "/data"

      # RELATIVE path to the node/file content that could be mounted in other containers
      subPath: # e.g. "cavern"

      # See https://github.com/opencadc/vos/tree/master/cavern for documentation.  For deployments using OpenID Connect,
      # the rootOwner MUST be an object with the following properties set.
      rootOwner:
        # The adminUsername is required to be set whomever has admin access over the filesystem.dataDir above.
        adminUsername: 

        # The username of the root owner.
        username: 

        # The UID of the root owner.
        uid: 

        # The GID of the root owner.
        gid: 

    # Further UWS settings for the Tomcat Pool setup.
    uws:
      install: true
      schema: uws
      maxActive: 2

    # Optional rename of the application from the default "cavern"
    # applicationName: "cavern"

    # The endpoint to serve this from.  Defaults to /cavern.  If the applicationName is changed, then this should match.
    # Don't forget to update your registry entries!
    #
    # endpoint: "/cavern"

    # Simple Class name of the QuotaPlugin to use.  This is used to request quota and folder size information
    # from the underlying storage system.  Optional, defaults to NoQuotaPlugin.
    #
    # - For CephFS deployments: CephFSQuotaPlugin
    # - Default: NoQuotaPlugin
    #
    # quotaPlugin: {NoQuotaPlugin | CephFSQuotaPlug}

    # Optionally set the DEBUG port.
    #
    # Example:
    # extraEnv:
    # - name: CATALINA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    # - name: JAVA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    #
    # extraEnv:

    # Optionally mount a custom CA certificate
    # Example:
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume
    # 
    # extraVolumeMounts:

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # Example:
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: cavern-cacert-secret
    #
    # extraVolumes:

    # Other data to be included in the main ConfigMap of this deployment.
    # Of note, files that end in .key are special and base64 decoded.
    # 
    # extraConfigData:
    
    # Resources provided to the Cavern service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Optionally describe how this Pod will be scheduled using the nodeAffinity clause. This applies to Cavern.
    # See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/
    # Example:
    nodeAffinity:
      # Only allow Cavern to run on specific Nodes.
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: kubernetes.io/hostname
            operator: In
            values:
            - my-special-api-host

  # Specify extra hostnames that will be added to the Pod's /etc/hosts file.  Note that this is in the
  # deployment object, not the cavern one.
  #
  # These entries get added as hostAliases entries to the Deployment.
  #
  # Example:
  # extraHosts:
  #   - ip: 127.3.34.5
  #     hostname: myhost.example.org
  #
  # extraHosts: []

# secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
  # cavern-cacert-secret:
  #   ca.crt: <base64 encoded CA crt>

# Set these appropriately to match your Persistent Volume Claim labels.
storage:
  service:
    spec:
      # YAML for service mounted storage.
      # Example is the persistentVolumeClaim below.  This should match whatever Skaha used.
      # persistentVolumeClaim:
      #   claimName: skaha-pvc

# UWS Database
postgresql:
  install: true  # To run your own database, set this to false and override auth settings.
```

### User Storage UI installation

Create a `my-storage-ui-local-values-file.yaml` file to override Values from the main [template `values.yaml` file](storage-ui/values.yaml).

`my-storage-ui-local-values-file.yaml`
```yaml
deployment:
  hostname: example.org
  storageUI:
    # OIDC (IAM) server configuration.  These are required
    oidc:
      # Location of the OpenID Provider (OIdP), and where users will login
      uri: https://iam.example.org/

      # The Client ID as listed on the OIdP.  Create one at the uri above.
      clientID: my-client-id

      # The Client Secret, which should be generated by the OIdP.
      clientSecret:  my-client-secret

      # Where the OIdP should send the User after successful authentication.  This is also known as the redirect_uri in OpenID.
      redirectURI: https://example.com/science-portal/oidc-callback

      # Where to redirect to after the redirectURI callback has completed.  This will almost always be the URL to the /science-portal main page (https://example.com/science-portal).
      callbackURI: https://example.com/science-portal/

      # The standard OpenID scopes for token requests.  This is required, and if using the SKAO IAM, can be left as-is.
      scope: "openid profile offline_access"

    # ID (URI) of the GMS Service.
    gmsID: ivo://example.org/gms

    # Dictionary of all VOSpace APIs (Services) available that will be visible on the UI.
    # Format is:
    backend:
      defaultService: cavern
      services:
        cavern:
          resourceID: "ivo://example.org/cavern"
          nodeURIPrefix: "vos://example.org~cavern"
          userHomeDir: "/home"
          # Some VOSpace services support these features.  Cavern does not, but it needs to be explicitly declared here.
          features:
            batchDownload: false
            batchUpload: false
            externalLinks: false
            paging: false

    # Optionally mount a custom CA certificate
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: storage-ui-cacert-secret

    # Other data to be included in the main ConfigMap of this deployment.
    # Of note, files that end in .key are special and base64 decoded.
    # 
    # extraConfigData:
    
    # Resources provided to the StorageUI service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Optionally describe how this Pod will be scheduled using the nodeAffinity clause. This applies to the Storage UI Pod(s).
    # See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/
    # Example:
    nodeAffinity:
      # Only allow Storage UI to run on specific Nodes.
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: kubernetes.io/hostname
            operator: In
            values:
            - my-special-ui-host

  # Specify extra hostnames that will be added to the Pod's /etc/hosts file.  Note that this is in the
  # deployment object, not the storageUI one.
  #
  # These entries get added as hostAliases entries to the Deployment.
  #
  # Example:
  # extraHosts:
  #   - ip: 127.3.34.5
  #     hostname: myhost.example.org
  #
  # extraHosts: []

# secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
  # storage-ui-cacert-secret:
    # ca.crt: <base64 encoded ca.crt blob>
```

## Obtaining a Bearer Token

See the [JIRA Confluence page](https://confluence.skatelescope.org/display/SRCSC/RED-10+Using+oidc-agent+to+authenticate+to+OpenCADC+services) on obtaining a Bearer Token.

## Flow

![Skaha Flow](SkahaDependFlow.png)

The Skaha service depends on several installations being in place.

## Structure

![Simple Skaha structure](./skaha.png)
