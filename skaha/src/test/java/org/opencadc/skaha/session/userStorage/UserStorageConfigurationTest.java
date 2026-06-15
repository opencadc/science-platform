package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.util.Log4jInit;
import java.net.URI;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class UserStorageConfigurationTest {
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.DEBUG);
    }

    @BeforeClass
    public static void setUpBeforeClass() {
        System.setProperty(
                UserStorageConfiguration.SKAHA_USER_STORAGE_USER_HOME_URI, "vos://storage.example.com~cavern/home/");
        System.setProperty(UserStorageConfiguration.SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY, "/data");
        System.setProperty(UserStorageConfiguration.SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY, "home");
        System.setProperty(UserStorageConfiguration.SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY, "projects");
    }

    @Test
    public void testUserStorageConfiguration() {
        final UserStorageConfiguration testSubject = UserStorageConfiguration.fromEnv();

        Assert.assertEquals("Wrong serviceURI", URI.create("ivo://storage.example.com/cavern"), testSubject.serviceURI);
    }
}
