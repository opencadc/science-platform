package org.opencadc.skaha.session;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.metrics.DummyMetricsDAO;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PlatformClusterResourceFields;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;
import org.opencadc.skaha.metrics.PlatformMetricsMapper;

public class GetActionResourceStatsTest {

    private static final NodeDAO.AggregatedCapacity DISTINCT_NODE_CAPACITY = new NodeDAO.AggregatedCapacity(
            999.0, 999_000_000_000L, 0, Map.entry(16.0, 64_000_000_000L), Map.entry(64_000_000_000L, 16.0));

    @Test
    public void clusterTotalsComeFromPlatformMetricsNotNodeOrSessionTotals() throws Exception {
        final PlatformClusterResourceFields expectedClusterFields =
                PlatformMetricsMapper.map(PlatformMetricsFixtures.fixedPlatformMetrics());
        final GetAction get = new TestableGetAction(new DummyMetricsDAO(), DISTINCT_NODE_CAPACITY);

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

    private static final class TestableGetAction extends GetAction {
        private final NodeDAO.AggregatedCapacity nodeCapacity;

        TestableGetAction(final MetricsDAO metricsDAO, final NodeDAO.AggregatedCapacity nodeCapacity) {
            super(metricsDAO);
            this.nodeCapacity = nodeCapacity;
        }

        @Override
        NodeDAO.AggregatedCapacity loadNodeCapacity() throws Exception {
            return nodeCapacity;
        }
    }
}
