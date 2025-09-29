package org.opencadc.skaha.session;

public class TestUtil {
    static void setupUserStorageEnvironment() {
        System.setProperty("SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY", "/tmp");
        System.setProperty("SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY", "/tmp/skaha-test");
        System.setProperty("SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY", "/tmp/skaha-test-projects");
        System.setProperty("SKAHA_USER_STORAGE_SERVICE_URI", "ivo://example.org/skaha/userStorage");
        System.setProperty("SKAHA_USER_STORAGE_USER_HOME_URI", "vos://example.org~skaha/home");
        System.setProperty("SKAHA_USER_STORAGE_USER_PROJECTS_URI", "vos://example.org~skaha/projects");
    }

    static void tearDownUserStorageEnvironment() {
        System.getProperties().remove("SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY ");
        System.getProperties().remove("SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY");
        System.getProperties().remove("SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY");
        System.getProperties().remove("SKAHA_USER_STORAGE_SERVICE_URI");
        System.getProperties().remove("SKAHA_USER_STORAGE_USER_HOME_URI");
        System.getProperties().remove("SKAHA_USER_STORAGE_USER_PROJECTS_URI");
    }
}
