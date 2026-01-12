package org.opencadc.skaha.session;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.X509CertificateChain;
import ca.nrc.cadc.cred.CertUtil;
import ca.nrc.cadc.cred.client.CredClient;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.ResourceAlreadyExistsException;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import javax.security.auth.Subject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.session.userStorage.UserStorageAdminConfiguration;
import org.opencadc.skaha.session.userStorage.UserStorageConfiguration;
import org.opencadc.skaha.utils.CommonUtils;
import org.opencadc.vospace.ContainerNode;
import org.opencadc.vospace.Node;
import org.opencadc.vospace.NodeProperty;
import org.opencadc.vospace.VOS;
import org.opencadc.vospace.VOSURI;
import org.opencadc.vospace.client.ClientTransfer;
import org.opencadc.vospace.client.VOSpaceClient;
import org.opencadc.vospace.transfer.Direction;
import org.opencadc.vospace.transfer.Protocol;
import org.opencadc.vospace.transfer.Transfer;

public class UserStorageClient {
    private static final Logger LOGGER = Logger.getLogger(UserStorageClient.class);
    private static final double ONE_WEEK_DAYS = 7.0D;
    private static final String PROXY_CERTIFICATE_FOLDER_NAME = ".ssl";
    private static final File DESKTOP_USER_HOME_PREPARATION_BUNDLE_FILE =
            new File("/add-user-config/desktop-config.tar");
    private static final String DESKTOP_USER_HOME_PREPARATION_FILES_PATH_CHECK_FILENAME = ".bashrc";

    private final UserStorageAdminConfiguration userStorageAdminConfiguration;
    private final UserStorageConfiguration userStorageConfiguration;

    public UserStorageClient() {
        this(UserStorageAdminConfiguration.fromEnv(), UserStorageConfiguration.fromEnv());
    }

    public UserStorageClient(
            final UserStorageAdminConfiguration userStorageAdminConfiguration,
            final UserStorageConfiguration userStorageConfiguration) {
        this.userStorageAdminConfiguration = userStorageAdminConfiguration;
        this.userStorageConfiguration = userStorageConfiguration;
    }

    /**
     * Ensures the existence of a user home in the VOSpace service for the given user.
     *
     * @param owner The owner of the new user home.
     * @throws Exception if there is an error during allocation.
     */
    public void ensureUserBase(final String owner) throws Exception {
        final VOSpaceClient cavernClient = new VOSpaceClient(this.userStorageConfiguration.serviceURI);
        final String userHomeBasePath = this.userStorageConfiguration.userHomeBaseURI.getPath();
        final String userHomePath = Path.of(userHomeBasePath, owner).toString();

        // Call as null user to ensure that the owner is properly augmented without the actual current user in the
        // context.
        final Subject adminOwner = Subject.callAs(null, this.userStorageAdminConfiguration.owner::toSubject);

        try {
            // Run this as the current user.
            cavernClient.getNode(userHomePath);
            LOGGER.debug("User home already exists: " + userHomePath);
        } catch (ResourceNotFoundException nodeNotFoundException) {
            LOGGER.debug("User home does not exist, allocating new user home at " + userHomePath);
            allocateUser(adminOwner, owner, cavernClient, this.userStorageAdminConfiguration);
            LOGGER.debug("User home does not exist, allocating new user home at " + userHomePath + ": OK");
        }
    }

    /**
     * Inject a proxy certificate into the user's home directory, if available. This only applies to users running
     * inside the CANFAR/CADC deployment.
     *
     * <p>The certificate is stored in the user's home directory at <code>${HOME}/<owner>/.ssl/cadcproxy.pem</code>.
     *
     * @param owner The owner of the user home where the proxy certificate will be injected.
     */
    void injectProxyCertificate(final String owner) {
        LOGGER.debug("injectProxyCertificate()");

        // inject a delegated proxy certificate if available
        try {
            final URI credServiceID = CommonUtils.firstLocalServiceURI(Standards.CRED_PROXY_10);

            // Only CADC installations will have a cred service configured.
            if (credServiceID != null) {
                final RegistryClient registryClient = new RegistryClient();
                final URL credServiceURL =
                        registryClient.getServiceURL(credServiceID, Standards.CRED_PROXY_10, AuthMethod.CERT);

                if (credServiceURL != null) {
                    final VOSpaceClient cavernClient = new VOSpaceClient(userStorageConfiguration.serviceURI);
                    final Path sslHomeFolder = Path.of(
                            this.userStorageConfiguration.userHomeBaseURI.getPath(),
                            owner,
                            UserStorageClient.PROXY_CERTIFICATE_FOLDER_NAME);
                    try {
                        cavernClient.getNode(sslHomeFolder.toString());
                        LOGGER.debug("SSL folder already exists: " + sslHomeFolder.toAbsolutePath());
                    } catch (ResourceNotFoundException resourceNotFoundException) {
                        // Create the folder if not already present.
                        final ContainerNode sslHiddenFolderNode =
                                new ContainerNode(UserStorageClient.PROXY_CERTIFICATE_FOLDER_NAME);
                        final URI sslFolderURI = URI.create(userStorageConfiguration.userHomeBaseURI + "/"
                                + Path.of(owner, UserStorageClient.PROXY_CERTIFICATE_FOLDER_NAME));
                        final Node createdNode = cavernClient.createNode(new VOSURI(sslFolderURI), sslHiddenFolderNode);
                        if (!(createdNode instanceof ContainerNode)) {
                            throw new IllegalStateException("BADNESS: Created Node is not a ContainerNode: "
                                    + createdNode
                                    + ".  Expected ContainerNode called " + sslHiddenFolderNode.getName() + " at "
                                    + userStorageConfiguration.userHomeBaseURI.getPath());
                        } else {
                            LOGGER.debug("Created SSL folder: " + sslHomeFolder.toAbsolutePath());
                        }
                    }

                    final String certificateFileName = "cadcproxy.pem";
                    final CredClient credClient = new CredClient(credServiceID);
                    final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
                    final X509CertificateChain proxyCert = Subject.callAs(
                            CredUtil.createOpsSubject(),
                            () -> credClient.getProxyCertificate(currentSubject, UserStorageClient.ONE_WEEK_DAYS));

                    LOGGER.debug("Proxy cert: " + proxyCert);
                    final URI nodeURI = URI.create(this.userStorageConfiguration.userHomeBaseURI + "/" + owner + "/"
                            + Path.of(UserStorageClient.PROXY_CERTIFICATE_FOLDER_NAME, certificateFileName));
                    final Transfer transfer = new Transfer(nodeURI, Direction.pushToVoSpace);
                    transfer.getProtocols().add(new Protocol(VOS.PROTOCOL_HTTPS_PUT));

                    final ClientTransfer clientTransfer = cavernClient.createTransfer(transfer);
                    clientTransfer.setOutputStreamWrapper(out -> {
                        CertUtil.writePEMCertificateAndKey(proxyCert, new OutputStreamWriter(out));
                        out.flush();
                    });
                    clientTransfer.setMonitor(true);
                    clientTransfer.runTransfer();

                    LOGGER.debug("injectProxyCertificate(): OK -> ");
                } else {
                    LOGGER.debug("Not using proxy certificates");
                    LOGGER.debug("injectProxyCertificate(): SKIPPED");
                }
            }
        } catch (ResourceNotFoundException resourceNotFoundException) {
            LOGGER.debug("No home node found for user: " + owner);
            LOGGER.debug("injectProxyCertificate(): UNSUCCESSFUL");
        } catch (Exception e) {
            LOGGER.warn("failed to inject cert: " + e.getMessage(), e);
            LOGGER.debug("injectProxyCertificate(): UNSUCCESSFUL");
        }
    }

    void ensureDesktopUserHomePreparation(final String owner) throws Exception {
        LOGGER.debug("UserStorageClient.ensureDesktopUserHomePreparation()");
        final VOSpaceClient cavernClient = new VOSpaceClient(this.userStorageConfiguration.serviceURI);
        final String fileCheckPath = Path.of(
                        this.userStorageConfiguration.userHomeBaseURI.getPath(),
                        owner,
                        UserStorageClient.DESKTOP_USER_HOME_PREPARATION_FILES_PATH_CHECK_FILENAME)
                .toString();
        try {
            cavernClient.getNode(fileCheckPath);
            LOGGER.debug("Desktop setup presumed already exists: " + fileCheckPath);
        } catch (ResourceNotFoundException nodeNotFoundException) {
            final Path desktopSetupFileRoot = UserStorageClient.extractDesktopSetupFiles(
                    UserStorageClient.DESKTOP_USER_HOME_PREPARATION_BUNDLE_FILE);

            LOGGER.debug("Desktop setup does not exist, extracting files...");
            LOGGER.debug("Desktop setup extracted to " + desktopSetupFileRoot + ", uploading files to " + owner
                    + " user home.");

            try {
                final long startTime = System.currentTimeMillis();
                walkFolder(cavernClient, desktopSetupFileRoot.toFile(), desktopSetupFileRoot, owner);
                final long endTime = System.currentTimeMillis();
                LOGGER.debug(
                        "Desktop setup files uploaded to " + owner + " user home: " + (endTime - startTime) + " ms");
            } catch (ResourceAlreadyExistsException resourceAlreadyExistsException) {
                LOGGER.warn(
                        "Attempted to upload desktop setup files, but they already exist in the user home: "
                                + owner + ".  This is likely due to a previous upload attempt, or deletion of "
                                + UserStorageClient.DESKTOP_USER_HOME_PREPARATION_FILES_PATH_CHECK_FILENAME
                                + "\n.  Abandoning upload.",
                        resourceAlreadyExistsException);
            }
            LOGGER.debug("Desktop setup does not exist, uploading files to " + owner + " user home: OK");
        }
        LOGGER.debug("UserStorageClient.ensureDesktopUserHomePreparation(): OK");
    }

    /**
     * Top-down walk of the given folder, creating folders and uploading files to the VOSpace service.
     *
     * @param voSpaceClient The VOSpace client to use for the upload.
     * @param folder The top level folder to walk.
     * @param root The root path of the user home in the VOSpace service. Used to resolve file locations.
     * @param owner The resource owner of the user home where the files will be uploaded.
     * @throws Exception For any upload or folder creation issues.
     */
    void walkFolder(final VOSpaceClient voSpaceClient, final File folder, final Path root, final String owner)
            throws Exception {
        final File[] files = folder.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isDirectory()) {
                    createFolder(voSpaceClient, file.toPath(), root, owner);
                    walkFolder(voSpaceClient, file, root, owner);
                } else {
                    upload(voSpaceClient, file, root, owner);
                }
            }
        }
    }

    private void upload(final VOSpaceClient voSpaceClient, final File file, final Path root, final String owner)
            throws Exception {
        LOGGER.debug("UserStorageClient.upload() - Uploading file: " + file.getName());
        final Path relativePath = root == null ? file.toPath() : root.relativize(file.toPath());
        final URI nodeURI =
                URI.create(this.userStorageConfiguration.userHomeBaseURI + "/" + owner + "/" + relativePath);
        final Transfer transfer = new Transfer(nodeURI, Direction.pushToVoSpace);
        transfer.getProtocols().add(new Protocol(VOS.PROTOCOL_HTTPS_PUT));

        final ClientTransfer clientTransfer = voSpaceClient.createTransfer(transfer);
        clientTransfer.setFile(file);
        clientTransfer.setMonitor(true);
        clientTransfer.runTransfer();
        LOGGER.debug("UserStorageClient.upload() - Uploading file: " + file.getName() + ": OK");
    }

    private void createFolder(
            final VOSpaceClient voSpaceClient, final Path folderPath, final Path root, final String owner)
            throws Exception {
        LOGGER.debug("UserStorageClient.createFolder() - Creating folder: " + folderPath.getFileName());
        final ContainerNode node = new ContainerNode(folderPath.getFileName().toString());
        final Path relativePath = root.relativize(folderPath);
        final URI nodeURI =
                URI.create(this.userStorageConfiguration.userHomeBaseURI + "/" + owner + "/" + relativePath);
        voSpaceClient.createNode(new VOSURI(nodeURI), node, false);
        LOGGER.debug("UserStorageClient.createFolder() - Creating folder: " + folderPath.getFileName() + ": OK");
    }

    /**
     * Call the Cavern service to allocate a new user home. The new User Allocation (ContainerNode) will be created with
     * the appropriate "creator" (Resource Owner) set, and the default quota.
     *
     * @param adminOwner The owner of the allocation folder (i.e. /home) to create the user home in.
     * @param voSpaceClient An existing VOSpace client
     * @param userStorageAdminConfiguration The configuration to connect to the Cavern service.
     * @throws IOException For any issues with writing the Node to the Cavern service.
     */
    void allocateUser(
            final Subject adminOwner,
            final String owner,
            final VOSpaceClient voSpaceClient,
            final UserStorageAdminConfiguration userStorageAdminConfiguration)
            throws IOException {
        LOGGER.debug("UserStorageClient.allocateUser()");
        try {
            final ContainerNode userHomeNode = new ContainerNode(owner);
            userHomeNode.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_QUOTA, K8SUtil.getDefaultQuotaBytes()));
            userStorageAdminConfiguration.configureOwner(userHomeNode, AuthenticationUtil.getCurrentSubject());

            final ContainerNode newUserHome = Subject.callAs(adminOwner, () -> {
                final Node createdNode = voSpaceClient.createNode(
                        new VOSURI(this.userStorageConfiguration.userHomeBaseURI + "/" + owner), userHomeNode, false);
                if (createdNode instanceof ContainerNode) {
                    return (ContainerNode) createdNode;
                } else {
                    throw new IllegalStateException("BADNESS: Created Node is not a ContainerNode: " + createdNode
                            + ".  Expected ContainerNode called " + userHomeNode.getName() + " at "
                            + this.userStorageConfiguration.userHomeBaseURI.getPath());
                }
            });

            LOGGER.debug("UserStorageClient.allocateUser(): OK -> " + newUserHome.getName());
        } catch (CompletionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException(cause.getMessage(), cause);
            }
        }
    }

    /**
     * Get the list of desktop setup files that should be uploaded to a user's home directory. Will be sorted by path
     * structure (the path depth).
     *
     * @return The Path where the files are located.
     * @throws IOException If the TAR file cannot be read or processed.
     */
    static Path extractDesktopSetupFiles(final File sourceFile) throws IOException {
        final Path outputDirectoryPath = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "")
                .normalize();
        try (final TarArchiveInputStream tarArchiveInputStream =
                new TarArchiveInputStream(new FileInputStream(sourceFile))) {
            TarArchiveEntry tarEntry;
            while ((tarEntry = tarArchiveInputStream.getNextEntry()) != null) {
                if (tarEntry.isFile()) {
                    // Create the directory structure in the output directory.
                    final Path filePath =
                            outputDirectoryPath.resolve(tarEntry.getName()).normalize();
                    if (filePath.startsWith(outputDirectoryPath)) {
                        // Ensure the file path is within the output directory.
                        LOGGER.debug("Extracting file: " + filePath);
                        if (filePath.getParent() != null) {
                            try {
                                Files.createDirectories(filePath.getParent());
                            } catch (FileAlreadyExistsException fileAlreadyExistsException) {
                                // Directory already exists, ignore.
                                LOGGER.debug("Directory already exists: " + filePath.getParent());
                            }
                        }
                        try (final FileOutputStream fileOutputStream = new FileOutputStream(filePath.toFile())) {
                            IOUtils.copy(tarArchiveInputStream, fileOutputStream);
                        }
                    } else {
                        throw new IOException("Invalid file path: " + filePath + ".  Extraction outside of "
                                + outputDirectoryPath + " is not allowed.");
                    }
                }
            }
        }

        return outputDirectoryPath;
    }
}
