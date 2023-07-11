package org.opencadc.skaha.util;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

public class IamConnector {
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String GRANT_TYPE = "grant_type";
    public static final String ACCESS_TOKEN = "access_token";
    private String connectionString;

    RestTemplate restTemplate = new RestTemplate();

    private IamConnector() {
    }

    private IamConnector(String connectionString) {
        this.connectionString = connectionString;
    }

    public static IamConnector build(String connectionString) {
        return new IamConnector(connectionString);
    }

    public String bearerTokenFromPasswordGrantFlowClient(String username, String password, String clientId, String clientSecret) {
        Map<String, Object> response = callPasswordGrantFlowClient(username, password, clientId, clientSecret);
        return "Bearer " + response.get(ACCESS_TOKEN);
    }

    public Map<String, Object> callPasswordGrantFlowClient(String username, String password, String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
        requestBody.add(USERNAME, username);
        requestBody.add(PASSWORD, password);
        requestBody.add(CLIENT_ID, clientId);
        requestBody.add(CLIENT_SECRET, clientSecret);
        requestBody.add(GRANT_TYPE, PASSWORD);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(requestBody, headers);
        return restTemplate.exchange(
                        this.connectionString + "/token",
                        HttpMethod.POST,
                        entity,
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        })
                .getBody();
    }
}
