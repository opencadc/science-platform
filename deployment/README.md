# Science Platform Deployment

## APIs

### Skaha API

The Skaha Service will manage User Sessions.  It interfaces with Kubernetes through the `kubectl` command to list, create, and delete sessions.  The SKaha API runs inside the Kubernetes cluster as well inside the `skaha-system` namespace, but User Sessions created will run in the `skaha-workload` namespace.  This can be called directly, or by the Science Portal User Interface.  Authentication is mandatory.

### POSIX Mapper API

The POSIX Mapper Service is a simple API to store existing usernames and map them with a generated UID.  Similarly, it will store existing group names with GIDs.  This can be called directly, but is mostly called by services wishing to map a username or group name to its assoicated numeric POSIX ID.  The POSIX Mapper API runs inside the Kubernetes cluster inside the `skaha-system` namespace.  Authentication is mandatory.

### Cavern API

Cavern is access to the underlying User Storage.  Anonymous access is allowed for any public content, but private files and folders will be denied access.  This service will run inside the `skaha-system` namespace within Kubernetes.

### Science Portal Application

Browser based application to interaface with authentication as well as the Skaha API.

### Storage UI Application

Browser based application to interaface with authentication as well as the Cavern API.

## Endpoints

| Endpoint   | Service  | Purpose |
|:----------|:---------|:---------|
| `/skaha`   | `skaha`  | Versioned (`/v0`) API to provide access to session managment as well as the IVOA capabilities (`/capabilities`) and availability (`/availability`) endpoints.  Visit the `/skaha` endpoint in the browser to see all available endpoints. |
| `/session` | `skaha`  | Provides access to the User Sessions (i.e. Desktop, Notebook) from the browser.  This endpoint is dynamically generated, and cannot be used by itself.  See the session listing from the `/skaha/v0/session` endpoint to get the full session endpoint. |
| `/posix-mapper` | `posix-mapper` | Provides access to the UID and GID mapping in plain POSIX text or TSV output.  Visit the `/posix-mapper` endpoint in the browser to see all available endpoints. |
| `/cavern` | `cavern` | [IVOA VOSpace](https://www.ivoa.net/documents/VOSpace/20180620/REC-VOSpace-2.1.html) endpoint for accessing the User Storage. |
| `/science-portal` | `science-portal` | Browser user interface endpoint for session listing, creation, and deletion.  This is a Single Page Application (SPA) using React and Plain Javascript, with a Java backend. |
| `/storage` | `storage-ui` | Browser application to manage Storage Items in the User Storage API (`/cavern`).  It provides Upload and Download, Folder creation, and Group setting. |


## OpenID Connect Authentication