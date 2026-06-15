package org.opencadc.skaha;

import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.Log4jInit;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import javax.security.auth.Subject;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.opencadc.skaha.context.GetAction;
import org.opencadc.skaha.metrics.PlatformMetrics.ClusterResourceFields;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;

/**
 * Integration tests for {@code GET /v1/session?view=stats} (platform stats).
 *
 * <p>Validates the stats JSON schema against a live Skaha deployment. When {@code SKAHA_METRICS_INTTEST_MODE} is set to
 * {@code fixture}, also asserts cluster totals match {@link PlatformMetricsFixtures} (requires the Metrics backend stub
 * to return that fixture in the test environment).
 */
public class PlatformStatsIntTest {

    private static final String METRICS_INTTEST_MODE_ENV = "SKAHA_METRICS_INTTEST_MODE";
    private static final String FIXTURE_MODE = "fixture";

    private static final Logger log = Logger.getLogger(PlatformStatsIntTest.class);

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }

    private final URL sessionURL;
    private final AuthenticatedUser authenticatedUser;

    public PlatformStatsIntTest() throws Exception {
        final RegistryClient regClient = new RegistryClient();
        this.authenticatedUser = TestConfiguration.getCurrentUser();
        this.sessionURL = regClient.getServiceURL(
                TestConfiguration.getSkahaServiceID(), Standards.PLATFORM_SESSION_1, this.authenticatedUser.authMethod);
        log.info("sessions URL: " + sessionURL);
    }

    @Test
    public void platformStatsMatchesSchema() throws Exception {
        Subject.doAs(authenticatedUser.subject, (PrivilegedExceptionAction<Void>) () -> {
            final JSONObject stats = SessionUtil.getStats(sessionURL);
            validateStatsSchema(stats);
            assertClusterTotalsPresent(stats);
            return null;
        });
    }

    @Test
    public void platformStatsClusterTotalsMatchFixtureWhenConfigured() throws Exception {
        Assume.assumeTrue(FIXTURE_MODE.equalsIgnoreCase(System.getenv(METRICS_INTTEST_MODE_ENV)));

        Subject.doAs(authenticatedUser.subject, (PrivilegedExceptionAction<Void>) () -> {
            final JSONObject stats = SessionUtil.getStats(sessionURL);
            validateStatsSchema(stats);

            final ClusterResourceFields expected =
                    PlatformMetricsFixtures.fixedPlatformMetrics().toClusterResourceFields();
            final JSONObject cores = stats.getJSONObject("cores");
            final JSONObject ram = stats.getJSONObject("ram");

            Assert.assertEquals(expected.cpuCoresAvailable(), cores.getDouble("cpuCoresAvailable"), 0.0);
            Assert.assertEquals(expected.requestedCPUCores(), cores.getDouble("requestedCPUCores"), 0.0);
            Assert.assertEquals(expected.ramAvailable(), ram.getString("ramAvailable"));
            Assert.assertEquals(expected.requestedRAM(), ram.getString("requestedRAM"));
            return null;
        });
    }

    private static void validateStatsSchema(final JSONObject stats) throws Exception {
        try (final InputStream schemaStream = GetAction.class.getResourceAsStream("/stats-schema.json");
                final InputStreamReader schemaStreamReader = new InputStreamReader(schemaStream);
                final BufferedReader reader = new BufferedReader(schemaStreamReader)) {
            final StringBuilder builder = new StringBuilder();
            reader.lines().forEach(builder::append);
            final JSONObject rawSchema = new JSONObject(builder.toString());
            final Schema schema = SchemaLoader.load(rawSchema);
            schema.validate(stats);
        }
    }

    private static void assertClusterTotalsPresent(final JSONObject stats) {
        final JSONObject cores = stats.getJSONObject("cores");
        final JSONObject ram = stats.getJSONObject("ram");
        Assert.assertTrue(cores.has("cpuCoresAvailable"));
        Assert.assertTrue(cores.has("requestedCPUCores"));
        Assert.assertTrue(ram.has("ramAvailable"));
        Assert.assertTrue(ram.has("requestedRAM"));
        Assert.assertTrue(cores.has("maxCPUCores"));
        Assert.assertTrue(ram.has("maxRAM"));
    }
}
