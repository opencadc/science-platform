package org.opencadc.skaha.session;

import ca.nrc.cadc.net.TransientException;
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.rest.SyncOutput;
import ca.nrc.cadc.util.PropertiesReader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opencadc.skaha.context.LimitRangeResourceContext;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PodMetrics;
import org.opencadc.skaha.metrics.PlatformMetrics.ClusterResourceFields;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;

public class GetActionResourceStatsTest {

    private static final String CONFIG_DIR_PROPERTY = PropertiesReader.class.getName() + ".dir";

    private String previousConfigDir;

    @Before
    public void setUp() {
        previousConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
    }

    @After
    public void tearDown() {
        if (previousConfigDir == null) {
            System.clearProperty(CONFIG_DIR_PROPERTY);
        } else {
            System.setProperty(CONFIG_DIR_PROPERTY, previousConfigDir);
        }
    }

    @Test
    public void metricsDaoNotCreatedUntilResourceStatsRequested() throws Exception {
        final LazyMetricsGetAction get = new LazyMetricsGetAction();
        Assert.assertFalse(get.createMetricsDaoCalled);

        get.getResourceStats();

        Assert.assertTrue(get.createMetricsDaoCalled);
    }

    @Test
    public void publicConstructorDoesNotEagerlyCreateMetricsDao() {
        new GetAction();
    }

    @Test
    public void statsViewReturns503WhenPlatformMetricsUnavailable() throws Exception {
        final TestableGetAction get = new TestableGetAction(
                PlatformMetricsFixtures.failingPlatformMetricsDAO(), containerLimitRangeFixture(), true);
        get.configureStatsViewRequest();

        try {
            get.doAction();
            Assert.fail("Expected TransientException");
        } catch (TransientException transientException) {
            Assert.assertEquals(PlatformMetricsUnavailableException.CLIENT_MESSAGE, transientException.getMessage());
        }
    }

    @Test
    public void getResourceStatsThrowsWhenPlatformMetricsUnavailable() throws Exception {
        final TestableGetAction get = new TestableGetAction(
                PlatformMetricsFixtures.failingPlatformMetricsDAO(), containerLimitRangeFixture(), true);

        try {
            get.getResourceStats();
            Assert.fail("Expected PlatformMetricsUnavailableException");
        } catch (PlatformMetricsUnavailableException unavailable) {
            Assert.assertNotNull(unavailable.getCause());
        }
    }

    @Test
    public void statsViewReturns503WhenMetricsBackendNotConfigured() throws Exception {
        final TestableGetAction get = new TestableGetAction(
                new MetricsDAO(null, (userID, omitHeadless) -> PodMetrics.empty()),
                containerLimitRangeFixture(),
                true);
        get.configureStatsViewRequest();

        try {
            get.doAction();
            Assert.fail("Expected TransientException");
        } catch (TransientException transientException) {
            Assert.assertEquals(PlatformMetricsUnavailableException.CLIENT_MESSAGE, transientException.getMessage());
        }
    }

    @Test
    public void statsViewReturns503WhenLimitRangeEnabledButUnavailable() throws Exception {
        final TestableGetAction get =
                new TestableGetAction(PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics(), null, true);
        get.configureStatsViewRequest();

        try {
            get.doAction();
            Assert.fail("Expected TransientException");
        } catch (TransientException transientException) {
            Assert.assertEquals(SessionLimitRangeUnavailableException.CLIENT_MESSAGE, transientException.getMessage());
        }
    }

    @Test
    public void getResourceStatsThrowsWhenLimitRangeEnabledButUnavailable() throws Exception {
        final TestableGetAction get =
                new TestableGetAction(PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics(), null, true);

        try {
            get.getResourceStats();
            Assert.fail("Expected SessionLimitRangeUnavailableException");
        } catch (SessionLimitRangeUnavailableException unavailable) {
            Assert.assertNotNull(unavailable.getCause());
        }
    }

    @Test
    public void clusterTotalsComeFromPlatformMetricsNotNodeOrSessionTotals() throws Exception {
        final ClusterResourceFields expectedClusterFields =
                PlatformMetricsFixtures.fixedPlatformMetrics().toClusterResourceFields();
        final V1LimitRangeItem limitRange = containerLimitRangeFixture();
        final GetAction get =
                new TestableGetAction(PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics(), limitRange, true);

        final ResourceStats stats = get.getResourceStats();
        final JsonObject json = new Gson().toJsonTree(stats).getAsJsonObject();
        final JsonObject cores = json.getAsJsonObject("cores");
        final JsonObject ram = json.getAsJsonObject("ram");

        Assert.assertEquals(
                expectedClusterFields.cpuCoresAvailable(),
                cores.get("cpuCoresAvailable").getAsDouble(),
                0.0);
        Assert.assertEquals(
                expectedClusterFields.requestedCPUCores(),
                cores.get("requestedCPUCores").getAsDouble(),
                0.0);
        Assert.assertEquals(
                expectedClusterFields.ramAvailable(), ram.get("ramAvailable").getAsString());
        Assert.assertEquals(
                expectedClusterFields.requestedRAM(), ram.get("requestedRAM").getAsString());

        Assert.assertEquals(
                8.0, cores.getAsJsonObject("maxCPUCores").get("cpuCores").getAsDouble(), 0.0);
        Assert.assertEquals(
                "24G", cores.getAsJsonObject("maxCPUCores").get("withRam").getAsString());
        Assert.assertEquals("24G", ram.getAsJsonObject("maxRAM").get("ram").getAsString());
        Assert.assertEquals(
                8.0, ram.getAsJsonObject("maxRAM").get("withCPUCores").getAsDouble(), 0.0);
    }

    @Test
    public void sessionCeilingFromResourceContextWhenLimitRangeDisabled() throws Exception {
        final File configDir = new File(GetActionResourceStatsTest.class
                .getResource("/resource-context-test")
                .toURI());
        System.setProperty(CONFIG_DIR_PROPERTY, configDir.getAbsolutePath());

        final GetAction get =
                new TestableGetAction(PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics(), null, false);

        final ResourceStats stats = get.getResourceStats();
        final JsonObject json = new Gson().toJsonTree(stats).getAsJsonObject();
        final JsonObject cores = json.getAsJsonObject("cores");
        final JsonObject ram = json.getAsJsonObject("ram");

        Assert.assertEquals(
                5.0, cores.getAsJsonObject("maxCPUCores").get("cpuCores").getAsDouble(), 0.0);
        Assert.assertEquals(
                "20Gi", cores.getAsJsonObject("maxCPUCores").get("withRam").getAsString());
        Assert.assertEquals("20Gi", ram.getAsJsonObject("maxRAM").get("ram").getAsString());
        Assert.assertEquals(
                5.0, ram.getAsJsonObject("maxRAM").get("withCPUCores").getAsDouble(), 0.0);
    }

    private static V1LimitRangeItem containerLimitRangeFixture() {
        final V1LimitRangeItem containerLimitRange = new V1LimitRangeItem();
        containerLimitRange.setMax(Map.of("cpu", Quantity.fromString("8"), "memory", Quantity.fromString("24Gi")));
        containerLimitRange.setDefaultRequest(
                Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("2Gi")));
        containerLimitRange.setDefault(Map.of("cpu", Quantity.fromString("4"), "memory", Quantity.fromString("16Gi")));
        return containerLimitRange;
    }

    private static final class StatsViewSyncInput extends SyncInput {
        @Override
        public String getParameter(final String name) {
            if ("view".equals(name)) {
                return SessionAction.SESSION_VIEW_STATS;
            }
            return null;
        }

        @Override
        public String getPath() {
            return null;
        }
    }

    private static final class TestSyncOutput extends SyncOutput {
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }
    }

    private static class LazyMetricsGetAction extends GetAction {
        private boolean createMetricsDaoCalled;

        @Override
        protected MetricsDAO createMetricsDAO() {
            createMetricsDaoCalled = true;
            try {
                return PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        boolean isSessionLimitRangeEnabled() {
            return true;
        }

        @Override
        LimitRangeResourceContext loadLimitRangeResourceContext() {
            return new LimitRangeResourceContext(containerLimitRangeFixture());
        }
    }

    private static final class TestableGetAction extends GetAction {
        private final V1LimitRangeItem limitRangeItem;
        private final boolean sessionLimitRangeEnabled;

        TestableGetAction(
                final MetricsDAO metricsDAO,
                final V1LimitRangeItem limitRangeItem,
                final boolean sessionLimitRangeEnabled) {
            super(metricsDAO);
            this.limitRangeItem = limitRangeItem;
            this.sessionLimitRangeEnabled = sessionLimitRangeEnabled;
        }

        void configureStatsViewRequest() {
            syncInput = new StatsViewSyncInput();
            syncOutput = new TestSyncOutput();
        }

        @Override
        protected void initRequest() throws Exception {
            requestType = REQUEST_TYPE_SESSION;
            sessionID = null;
        }

        @Override
        boolean isSessionLimitRangeEnabled() {
            return sessionLimitRangeEnabled;
        }

        @Override
        LimitRangeResourceContext loadLimitRangeResourceContext() {
            if (limitRangeItem == null) {
                throw new SessionLimitRangeUnavailableException(
                        "Failed to load session LimitRange for stats",
                        new IllegalStateException("No limit ranges found in namespace"));
            }
            return new LimitRangeResourceContext(limitRangeItem);
        }
    }
}
