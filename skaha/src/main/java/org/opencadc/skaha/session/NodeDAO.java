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
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

public class NodeDAO {
    private static final Logger LOGGER = Logger.getLogger(NodeDAO.class.getName());

    /**
     * Query the API for the current available resources on all worker nodes, optionally filtered by label selector.
     *
     * @return Map where the key is the node name and the value is an array of strings representing CPU, memory, and GPU
     *     resources.
     * @throws Exception If there is an error communicating with the Kubernetes API.
     */
    static Map<String, String[]> getAvailableResources() throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();
        final CoreV1Api api = new CoreV1Api(client);

        // Make
        final String workerNodeLabelSelector = K8SUtil.getWorkerNodeLabelSelector();
        final CoreV1Api.APIlistNodeRequest listNodeRequest = api.listNode().fieldSelector("spec.unschedulable=false");
        if (StringUtil.hasLength(workerNodeLabelSelector)) {
            listNodeRequest.labelSelector(workerNodeLabelSelector);
            LOGGER.debug("Using worker node label selector: " + workerNodeLabelSelector);
        } else {
            LOGGER.warn("Worker node label selector is empty - selecting all schedulable Nodes");
        }

        return NodeDAO.getAvailableResources(listNodeRequest.execute());
    }

    /**
     * Process the given NodeList to extract available resources. Mainly used for testing.
     *
     * @param nodeList The list of nodes to process.
     * @return A map where the key is the node name and the value is an array of strings representing CPU, memory, and
     *     GPU resources.
     */
    static Map<String, String[]> getAvailableResources(final V1NodeList nodeList) {
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
                    final String gpu = capacity.get("nvidia.com/gpu") != null
                            ? capacity.get("nvidia.com/gpu").getNumber().toString()
                            : "0";
                    return new String[] {nodeName, cpu, memory, gpu};
                })
                .collect(Collectors.toUnmodifiableMap(arr -> arr[0], arr -> new String[] {arr[1], arr[2], arr[3]}));
    }

    protected static String toCoreUnit(String cores) {
        String ret = "<none>";
        if (StringUtil.hasLength(cores)) {
            if ("m".equals(cores.substring(cores.length() - 1))) {
                // in "m" (millicore) unit, covert to cores
                int milliCores = Integer.parseInt(cores.substring(0, cores.length() - 1));
                ret = ((Double) (milliCores / Math.pow(10, 3))).toString();
            } else {
                // use value as is, can be '<none>' or some value
                ret = cores;
            }
        }

        return ret;
    }
}
