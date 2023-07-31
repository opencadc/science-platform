package org.opencadc.skaha.util;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Map;

public class TestConfig {

    public static final String IAM = "iam";
    public static final String USER = "user";
    public static final String NAME = "name";
    public static final String PASSWORD = "password";
    public static final String CLIENT_ID = "clientId";
    public static final String CLIENT_SECRET = "clientSecret";

    public static final String TOKEN = "token";

    private static final Map<String, Object> config;
    public static final String TEST_URL = "testUrl";
    public static final String EXPECTED_STATUS_CODE = "expectedStatusCode";
    private String testName = null;

    private TestConfig() {
    }

    private TestConfig(String testName) {
        this.testName = testName;
    }

    static {
        config = buildConfig(System.getProperty("user.dir") + "/src/test/resources/test-config.yaml");
    }

    private static Map<String, Object> buildConfig(String path) {
        try {
            return new Yaml().load(new FileInputStream(path));
        } catch (FileNotFoundException e) {
            System.out.println("Exception is " + e.getMessage());
            throw new RuntimeException(e);
        }

    }

    public static TestConfig build(String testName) {
        return new TestConfig(testName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getTestConfig() {
        Map<String, Object> tests = (Map<String, Object>) config.get("tests");
        return (Map<String, Object>) tests.get(testName);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getAsMap(String keyName) {
        return (Map<String, String>) getTestConfig().get(keyName);
    }

    public String getAsString(String keyName) {
        return (String) getTestConfig().get(keyName);
    }

    public <T> T get(String keyName, Class<T> classType) {
        return classType.cast(getTestConfig().get(keyName));
    }

    public String testUrl() {
        return getAsString(TEST_URL);
    }

    public int expectedStatusCode() {
        return get(EXPECTED_STATUS_CODE, Integer.class);
    }

    public String fetchBearerToken() {
        Map<String, String> userConfig = getAsMap(USER);
        IamConnector iamConnector = IamConnector.build(getAsString(IAM));
        return iamConnector.bearerTokenFromPasswordGrantFlowClient(
                userConfig.get(NAME),
                userConfig.get(PASSWORD),
                userConfig.get(CLIENT_ID),
                userConfig.get(CLIENT_SECRET)
        );
    }

    public String getToken() {
        return getAsMap(USER).get(TOKEN);
    }
}
