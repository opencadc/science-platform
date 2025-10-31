package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1NodeStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

public class NodeDAO {
    private static final Logger LOGGER = Logger.getLogger(NodeDAO.class.getName());

    /**
     * Get the aggregated capacity across all Nodes.
     *
     * @return AggregatedCapacity object representing total and max capacities.
     * @throws Exception If there is an error retrieving node capacities.
     */
    static AggregatedCapacity getCapacity() throws Exception {
        final Set<Capacity> capacities = NodeDAO.getCapacities();

        double totalCores = 0.0;
        long totalMemoryBytes = 0L;
        int totalGpuCount = 0;

        Map.Entry<Double, Long> maxCorePairing = null;
        Map.Entry<Long, Double> maxMemoryPairing = null;

        for (final Capacity capacity : capacities) {
            final double cpuCores = Double.parseDouble(capacity.cpuCores());
            final long memoryBytes = Long.parseLong(capacity.memoryBytes());
            final int gpuCount = Integer.parseInt(capacity.gpuCount());

            totalCores += cpuCores;
            totalMemoryBytes += memoryBytes;
            totalGpuCount += gpuCount;

            if (maxCorePairing == null || cpuCores > maxCorePairing.getKey()) {
                maxCorePairing = Map.entry(cpuCores, memoryBytes);
            }

            if (maxMemoryPairing == null || memoryBytes > maxMemoryPairing.getKey()) {
                maxMemoryPairing = Map.entry(memoryBytes, cpuCores);
            }
        }

        return new AggregatedCapacity(totalCores, totalMemoryBytes, totalGpuCount, maxCorePairing, maxMemoryPairing);
    }

    /**
     * Query the API for the current available resources on all worker nodes, optionally filtered by label selector.
     *
     * @return Set of Capacity objects representing available resources on each node for CPU, memory, and GPU.
     * @throws Exception If there is an error communicating with the Kubernetes API.
     */
    static Set<Capacity> getCapacities() throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();
        final CoreV1Api api = new CoreV1Api(client);

        // Make
        final String workerNodeLabelSelector = K8SUtil.getWorkerNodeLabelSelector();
        final CoreV1Api.APIlistNodeRequest listNodeRequest = api.listNode().fieldSelector("spec.unschedulable=false");
        if (StringUtil.hasLength(workerNodeLabelSelector)) {
            listNodeRequest.labelSelector(workerNodeLabelSelector);
            LOGGER.debug("Using worker node label selector: " + workerNodeLabelSelector);
        } else {
            LOGGER.debug(
                    "Worker node label selector is empty - selecting all schedulable Nodes which is less efficient.");
        }

        return NodeDAO.getCapacities(listNodeRequest.execute());
    }

    /**
     * Process the given NodeList to extract available resources. Mainly used for testing.
     *
     * @param nodeList The list of nodes to process.
     * @return A Set of Capacity objects containing node name and the value is an array of strings representing CPU,
     *     memory, and GPU resources.
     */
    static Set<Capacity> getCapacities(final V1NodeList nodeList) {
        return nodeList.getItems().stream()
                .map(node -> {
                    final String nodeName = Objects.requireNonNullElse(node.getMetadata(), new V1ObjectMeta())
                            .getName();
                    final V1NodeStatus status = Objects.requireNonNullElse(node.getStatus(), new V1NodeStatus());
                    final Map<String, Quantity> capacity =
                            Objects.requireNonNullElse(status.getCapacity(), new HashMap<>());
                    final String cpu =
                            NodeDAO.toCoreUnit(Objects.requireNonNullElse(capacity.get("cpu"), new Quantity("0"))
                                    .getNumber()
                                    .toString());
                    final String memory = Objects.requireNonNullElse(capacity.get("memory"), new Quantity("0"))
                            .getNumber()
                            .toString();
                    final String gpu = capacity.get("nvidia.com/gpu.count") != null
                            ? capacity.get("nvidia.com/gpu.count").getNumber().toString()
                            : "0";
                    return new Capacity(nodeName, cpu, memory, gpu);
                })
                .collect(Collectors.toSet());
    }

    /**
     * Convert supplied cores string to standard core unit.
     *
     * @param cores String cores as reported by K8S API, possibly with unit suffix.
     * @return String cores in standard core unit.
     */
    protected static String toCoreUnit(String cores) {
        if (StringUtil.hasLength(cores)) {
            final String potentialUnit = cores.substring(cores.length() - 1);
            switch (potentialUnit) {
                case "m" -> {
                    // in "m" (millicore) unit, covert to cores
                    final int milliCores = Integer.parseInt(cores.substring(0, cores.length() - 1));
                    return ((Double) (milliCores / Math.pow(10, 3))).toString();
                }
                case "n" -> {
                    // in "n" (nanocore) unit, covert to cores
                    final long nanoCores = Long.parseLong(cores.substring(0, cores.length() - 1));
                    return ((Double) (nanoCores / Math.pow(10, 9))).toString();
                }
                default -> {
                    // no unit, assume cores
                    return cores;
                }
            }
        }

        return "<none>";
    }

    public record Capacity(String name, String cpuCores, String memoryBytes, String gpuCount) {}

    public record AggregatedCapacity(
            double totalCores,
            long totalMemoryBytes,
            int totalGpuCount,
            Map.Entry<Double, Long> maxCorePairing,
            Map.Entry<Long, Double> maxMemoryPairing) {}
}
