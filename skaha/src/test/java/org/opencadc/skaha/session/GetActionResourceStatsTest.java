package org.opencadc.skaha.session;

import ca.nrc.cadc.util.PropertiesReader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opencadc.skaha.metrics.DummyMetricsDAO;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PlatformClusterResourceFields;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;
import org.opencadc.skaha.metrics.PlatformMetricsMapper;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public class GetActionResourceStatsTest {

    private static final String CONFIG_DIR_PROPERTY = PropertiesReader.class.getName() + ".dir";

    private static final NodeDAO.AggregatedCapacity DISTINCT_NODE_CAPACITY = new NodeDAO.AggregatedCapacity(
            999.0, 999_000_000_000L, 0, Map.entry(16.0, 64_000_000_000L), Map.entry(64_000_000_000L, 16.0));

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
    public void clusterTotalsComeFromPlatformMetricsNotNodeOrSessionTotals() throws Exception {
        final PlatformClusterResourceFields expectedClusterFields =
                PlatformMetricsMapper.map(PlatformMetricsFixtures.fixedPlatformMetrics());
        final GetAction get = new TestableGetAction(new DummyMetricsDAO(), DISTINCT_NODE_CAPACITY, true);

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

        Assert.assertNotEquals(
                DISTINCT_NODE_CAPACITY.totalCores(),
                cores.get("cpuCoresAvailable").getAsDouble(),
                0.0);
        Assert.assertNotEquals(
                DISTINCT_NODE_CAPACITY.totalCores(),
                cores.get("requestedCPUCores").getAsDouble(),
                0.0);

        Assert.assertEquals(
                DISTINCT_NODE_CAPACITY.maxCorePairing().getKey(),
                cores.getAsJsonObject("maxCPUCores").get("cpuCores").getAsDouble(),
                0.0);
        Assert.assertEquals(
                DISTINCT_NODE_CAPACITY.maxMemoryPairing().getValue(),
                ram.getAsJsonObject("maxRAM").get("withCPUCores").getAsDouble(),
                0.0);
    }

    @Test
    public void sessionCeilingFromResourceContextWhenLimitRangeDisabled() throws Exception {
        final File configDir = new File(GetActionResourceStatsTest.class
                .getResource("/resource-context-test")
                .toURI());
        System.setProperty(CONFIG_DIR_PROPERTY, configDir.getAbsolutePath());

        final GetAction get = new TestableGetAction(new DummyMetricsDAO(), null, false);

        final ResourceStats stats = get.getResourceStats();
        final JsonObject json = new Gson().toJsonTree(stats).getAsJsonObject();
        final JsonObject cores = json.getAsJsonObject("cores");
        final JsonObject ram = json.getAsJsonObject("ram");

        Assert.assertEquals(
                5.0, cores.getAsJsonObject("maxCPUCores").get("cpuCores").getAsDouble(), 0.0);
        Assert.assertEquals(
                MemoryUnitConverter.formatHumanReadable(20, MemoryUnitConverter.MemoryUnit.Gi),
                cores.getAsJsonObject("maxCPUCores").get("withRam").getAsString());
        Assert.assertEquals(
                MemoryUnitConverter.formatHumanReadable(20, MemoryUnitConverter.MemoryUnit.Gi),
                ram.getAsJsonObject("maxRAM").get("ram").getAsString());
        Assert.assertEquals(
                5.0, ram.getAsJsonObject("maxRAM").get("withCPUCores").getAsDouble(), 0.0);
    }

    private static final class TestableGetAction extends GetAction {
        private final NodeDAO.AggregatedCapacity nodeCapacity;
        private final boolean sessionLimitRangeEnabled;

        TestableGetAction(
                final MetricsDAO metricsDAO,
                final NodeDAO.AggregatedCapacity nodeCapacity,
                final boolean sessionLimitRangeEnabled) {
            super(metricsDAO);
            this.nodeCapacity = nodeCapacity;
            this.sessionLimitRangeEnabled = sessionLimitRangeEnabled;
        }

        @Override
        boolean isSessionLimitRangeEnabled() {
            return sessionLimitRangeEnabled;
        }

        @Override
        NodeDAO.AggregatedCapacity loadNodeCapacity() throws Exception {
            return nodeCapacity;
        }
    }
}
