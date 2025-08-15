package org.opencadc.skaha.session;

import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Test;

public class SessionTypeTest {
    @Test
    public void testCARTAType() {
        final String currentWorkingDirectory = System.getProperty("user.home");
        final SessionType testSubject = SessionType.fromApplicationStringType("carta");
        Assert.assertEquals(
                "Wrong legacy ingress path",
                Path.of(currentWorkingDirectory + "/config/ingress-carta-legacy.yaml"),
                testSubject.getIngressConfigPath(true));

        Assert.assertEquals(
                "Wrong legacy service path",
                Path.of(currentWorkingDirectory + "/config/service-carta-legacy.yaml"),
                testSubject.getServiceConfigPath(true));

        Assert.assertEquals(
                "Wrong legacy job config path",
                Path.of(currentWorkingDirectory + "/config/launch-carta-legacy.yaml"),
                testSubject.getJobConfigPath(true));

        Assert.assertEquals(
                "Wrong ingress path",
                Path.of(currentWorkingDirectory + "/config/ingress-carta.yaml"),
                testSubject.getIngressConfigPath(false));

        Assert.assertEquals(
                "Wrong service path",
                Path.of(currentWorkingDirectory + "/config/service-carta.yaml"),
                testSubject.getServiceConfigPath(false));

        Assert.assertEquals(
                "Wrong job config path",
                Path.of(currentWorkingDirectory + "/config/launch-carta.yaml"),
                testSubject.getJobConfigPath(false));
    }

    @Test
    public void testNonCARTAType() {
        final String currentWorkingDirectory = System.getProperty("user.home");
        final SessionType testSubject = SessionType.fromApplicationStringType("notebook");
        Assert.assertEquals(
                "Wrong legacy ingress path",
                Path.of(currentWorkingDirectory + "/config/ingress-notebook.yaml"),
                testSubject.getIngressConfigPath(true));

        Assert.assertEquals(
                "Wrong legacy service path",
                Path.of(currentWorkingDirectory + "/config/service-notebook.yaml"),
                testSubject.getServiceConfigPath(true));

        Assert.assertEquals(
                "Wrong legacy job config path",
                Path.of(currentWorkingDirectory + "/config/launch-notebook.yaml"),
                testSubject.getJobConfigPath(true));

        Assert.assertEquals(
                "Wrong ingress path",
                Path.of(currentWorkingDirectory + "/config/ingress-notebook.yaml"),
                testSubject.getIngressConfigPath(false));

        Assert.assertEquals(
                "Wrong service path",
                Path.of(currentWorkingDirectory + "/config/service-notebook.yaml"),
                testSubject.getServiceConfigPath(false));

        Assert.assertEquals(
                "Wrong job config path",
                Path.of(currentWorkingDirectory + "/config/launch-notebook.yaml"),
                testSubject.getJobConfigPath(false));
    }
}
