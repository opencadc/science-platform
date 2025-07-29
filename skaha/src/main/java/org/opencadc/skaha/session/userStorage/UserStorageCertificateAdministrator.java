package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.X509CertificateChain;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.security.auth.Subject;

/** Represents a User Storage administrator that uses a PEM certificate for authentication. */
public class UserStorageCertificateAdministrator implements UserStorageAdministrator {
    private final byte[] certificateBytes;

    public UserStorageCertificateAdministrator(final byte[] certificateBytes) {
        this.certificateBytes = certificateBytes;
    }

    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     * @throws IOException if there is an error communicating with the user storage service or if the credentials are
     *     invalid.
     */
    @Override
    public Subject toSubject() throws IOException {
        final Subject subject = new Subject();

        try (final InputStream inputStream = new ByteArrayInputStream(this.certificateBytes)) {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            final Collection<? extends Certificate> certificateCollection =
                    certFactory.generateCertificates(inputStream);

            final List<X509Certificate> certificateList = certificateCollection.stream()
                    .filter(certificate -> certificate instanceof X509Certificate)
                    .map(certificate -> (X509Certificate) certificate)
                    .toList();

            subject.getPublicCredentials().add(new X509CertificateChain(certificateList));
        } catch (CertificateException e) {
            throw new IOException("Invalid certificate provided for User Storage admin: " + e.getMessage(), e);
        }

        final Subject validatedSubject = AuthenticationUtil.validateSubject(subject);
        return AuthenticationUtil.augmentSubject(validatedSubject);
    }
}
