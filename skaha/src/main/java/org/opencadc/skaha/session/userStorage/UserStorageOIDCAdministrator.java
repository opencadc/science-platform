package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationTokenPrincipal;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResourceOwnerPasswordCredentialsGrant;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.TokenTypeURI;
import com.nimbusds.oauth2.sdk.tokenexchange.TokenExchangeGrant;
import java.io.IOException;
import java.net.URI;
import javax.security.auth.Subject;

/**
 * Represents the administrator (owner) to be used for administrative operations on the user storage service using OIDC
 * client credentials.
 */
public class UserStorageOIDCAdministrator implements UserStorageAdministrator {
    private final ClientID clientID;
    private final Secret clientSecret;
    private final URI issuer;
    private final String adminUsername;
    private final byte[] adminPassword;

    /**
     * Constructs an administrator for the user storage service using OIDC client credentials.
     *
     * @param clientID The client ID of the OIDC client.
     * @param clientSecret The client secret of the OIDC client.
     * @param issuer The issuer URI of the OIDC provider.
     * @param adminUsername The username of the administrator.
     * @param adminPassword The password of the administrator, as a byte array.
     */
    public UserStorageOIDCAdministrator(
            final ClientID clientID,
            final Secret clientSecret,
            final URI issuer,
            final String adminUsername,
            final byte[] adminPassword) {
        this.clientID = clientID;
        this.clientSecret = clientSecret;
        this.issuer = issuer;
        this.adminUsername = adminUsername;

        this.adminPassword = new byte[adminPassword.length];
        System.arraycopy(adminPassword, 0, this.adminPassword, 0, adminPassword.length);
    }

    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     */
    @Override
    public Subject toSubject() throws IOException {
        final ClientSecretBasic oidcClientSecretBasic = new ClientSecretBasic(this.clientID, this.clientSecret);
        final AccessToken adminAccessToken = authenticateAdminUser();
        final TokenExchangeGrant tokenExchangeGrant =
                new TokenExchangeGrant(adminAccessToken, TokenTypeURI.ACCESS_TOKEN);
        final TokenRequest tokenRequest = new TokenRequest.Builder(
                        UserStorageAdminConfiguration.getTokenEndpoint(this.issuer),
                        oidcClientSecretBasic,
                        tokenExchangeGrant)
                .scope(new Scope("openid", "profile"))
                .build();
        final TokenResponse tokenResponse;

        try {
            tokenResponse = TokenResponse.parse(tokenRequest.toHTTPRequest().send());
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
            throw new IllegalStateException("Bad response from the OIDC server: " + tokenResponse.toErrorResponse());
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

    private AccessToken authenticateAdminUser() throws IOException {
        final ClientSecretBasic oidcClientSecretBasic = new ClientSecretBasic(this.clientID, this.clientSecret);
        final TokenRequest tokenRequest = new TokenRequest.Builder(
                        UserStorageAdminConfiguration.getTokenEndpoint(this.issuer),
                        oidcClientSecretBasic,
                        new ResourceOwnerPasswordCredentialsGrant(
                                this.adminUsername, new Secret(new String(this.adminPassword))))
                .build();
        final TokenResponse tokenResponse;

        try {
            tokenResponse = TokenResponse.parse(tokenRequest.toHTTPRequest().send());
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
                    + tokenResponse.toErrorResponse().getErrorObject());
        }

        final AccessTokenResponse tokenSuccessResponse = tokenResponse.toSuccessResponse();
        return tokenSuccessResponse.getTokens().getAccessToken();
    }
}
