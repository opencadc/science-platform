package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationTokenPrincipal;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretJWT;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.UUID;
import javax.security.auth.Subject;

/**
 * Represents the administrator (owner) to be used for administrative operations on the user storage service using OIDC
 * client credentials.
 */
public class UserStorageOIDCAdministrator implements UserStorageAdministrator {
    private static final String EXPECTED_SCOPE = "storage:allocations:write";

    private final ClientID clientID;
    private final Secret clientSecret;
    private final URI issuer;

    /**
     * Constructs an administrator for the user storage service using OIDC client credentials.
     *
     * @param clientID The client ID of the OIDC client.
     * @param clientSecret The client secret of the OIDC client.
     * @param issuer The issuer URI of the OIDC provider.
     */
    public UserStorageOIDCAdministrator(final ClientID clientID, final Secret clientSecret, final URI issuer) {
        this.clientID = clientID;
        this.clientSecret = clientSecret;
        this.issuer = issuer;
    }

    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     */
    @Override
    public Subject toSubject() throws IOException {
        final JWTClaimsSet signingPayload = new JWTClaimsSet.Builder()
                .audience(UserStorageAdminConfiguration.getTokenEndpoint(this.issuer)
                        .toString())
                .subject(this.clientID.getValue())
                .issuer(this.clientID.getValue())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(new Date(new Date().getTime() + (120 * 1000)))
                .build();

        final JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.HS256).build();
        final TokenResponse tokenResponse;

        try {
            final JWSSigner macSigner = new MACSigner(this.clientSecret.getValueBytes());
            final SignedJWT signedJWT = new SignedJWT(jwsHeader, signingPayload);

            signedJWT.sign(macSigner);

            final ClientSecretJWT jwtAuthentication = new ClientSecretJWT(signedJWT);
            final TokenRequest tokenRequest = new TokenRequest.Builder(
                            UserStorageAdminConfiguration.getTokenEndpoint(this.issuer),
                            jwtAuthentication,
                            new ClientCredentialsGrant())
                    .customParameter("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                    .scope(new Scope(UserStorageOIDCAdministrator.EXPECTED_SCOPE))
                    .build();
            tokenResponse = TokenResponse.parse(tokenRequest.toHTTPRequest().send());
        } catch (JOSEException keyGenerationException) {
            throw new IllegalStateException(
                    "Unable to generate JWT for OIDC client credentials: " + keyGenerationException.getMessage(),
                    keyGenerationException);
        } catch (ParseException parseException) {
            throw new IllegalArgumentException(
                    "Invalid or missing response parameters from token endpoint: " + parseException.getMessage(),
                    parseException);
        } catch (IOException ioException) {
            throw new IllegalStateException(
                    "Unable to send token request to the OIDC server: " + ioException.getMessage(), ioException);
        }

        if (!tokenResponse.indicatesSuccess()) {
            // We got an error response...
            throw new IllegalStateException("Bad response from the OIDC server: "
                    + tokenResponse.toErrorResponse().getErrorObject().getDescription());
        }

        final AccessTokenResponse tokenSuccessResponse = tokenResponse.toSuccessResponse();
        final AccessToken ownerAccessToken = tokenSuccessResponse.getTokens().getAccessToken();

        final AuthorizationTokenPrincipal authorizationTokenPrincipal = new AuthorizationTokenPrincipal(
                AuthenticationUtil.AUTHORIZATION_HEADER,
                AuthenticationUtil.CHALLENGE_TYPE_BEARER + " " + ownerAccessToken.getValue());

        final Subject subject = new Subject();
        subject.getPrincipals().add(authorizationTokenPrincipal);

        final Subject validatedSubject = AuthenticationUtil.validateSubject(subject);
        return AuthenticationUtil.augmentSubject(validatedSubject);
    }
}
