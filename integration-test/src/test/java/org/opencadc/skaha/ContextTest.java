package org.opencadc.skaha;

import org.junit.jupiter.api.Test;
import org.opencadc.skaha.util.TestConfig;

import static io.restassured.RestAssured.given;

;


public class ContextTest {
    public static final String AUTHORIZATION = "Authorization";


    @Test
    public void userIsPartOfGroup() {
        TestConfig testConfig = TestConfig.build("test-1");
        given().log().all()
                .header(AUTHORIZATION, testConfig.fetchBearerToken())
                .when()
                .get(testConfig.testUrl())
                .then()
                .statusCode(testConfig.expectedStatusCode());
    }


    @Test
    public void userIsNotPartOfGroup() {
        TestConfig testConfig = TestConfig.build("test-2");
        given().log().all()
                .header(AUTHORIZATION, testConfig.fetchBearerToken())
                .when()
                .get(testConfig.testUrl())
                .then()
                .statusCode(testConfig.expectedStatusCode());
    }

    @Test
    public void invalidToken() {
        TestConfig testConfig = TestConfig.build("test-3");
        given().log().all()
                .header(AUTHORIZATION, testConfig.getToken())
                .when()
                .get(testConfig.testUrl())
                .then()
                .statusCode(testConfig.expectedStatusCode());
    }


}
