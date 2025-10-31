package org.opencadc.skaha.session;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1NodeStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class NodeDAOTest {
    @Test
    public void testGetCapacities() {
        final V1NodeList nodeList = new V1NodeList();
        Assert.assertEquals("Wrong output.", Collections.emptySet(), NodeDAO.getCapacities(nodeList));

        final V1Node node1 = new V1Node();
        final Map<String, Quantity> node1Capacity = new HashMap<>();
        node1Capacity.put("cpu", new Quantity("4"));
        final V1NodeStatus node1Status = new V1NodeStatus().capacity(node1Capacity);
        node1.metadata(new V1ObjectMeta().name("node1")).status(node1Status);
        nodeList.addItemsItem(node1);

        final Set<NodeDAO.Capacity> expectedOutput = new HashSet<>();
        expectedOutput.add(new NodeDAO.Capacity("node1", "4", "0", "0"));

        Set<NodeDAO.Capacity> actualOutput1 = NodeDAO.getCapacities(nodeList);
        Assert.assertEquals("Wrong key count.", 1, expectedOutput.size());
        Assert.assertEquals("Wrong values for node1.", expectedOutput, actualOutput1);

        // Add memory to node1
        node1Capacity.put("memory", new Quantity("28Gi"));
        expectedOutput.clear();
        expectedOutput.add(new NodeDAO.Capacity("node1", "4", "30064771072", "0"));

        final Set<NodeDAO.Capacity> actualOutput2 = NodeDAO.getCapacities(nodeList);
        Assert.assertEquals("Wrong values for node2.", expectedOutput, actualOutput2);

        // Add node2
        final V1Node node2 = new V1Node();
        final Map<String, Quantity> node2Capacity = new HashMap<>();
        node2Capacity.put("cpu", new Quantity("16"));
        node2Capacity.put("memory", new Quantity("168Gi"));
        node2Capacity.put("nvidia.com/gpu.count", new Quantity("3"));
        final V1NodeStatus node2Status = new V1NodeStatus().capacity(node2Capacity);
        node2.metadata(new V1ObjectMeta().name("node2")).status(node2Status);
        nodeList.addItemsItem(node2);

        expectedOutput.clear();
        expectedOutput.add(new NodeDAO.Capacity("node1", "4", "30064771072", "0"));
        expectedOutput.add(new NodeDAO.Capacity("node2", "16", "180388626432", "3"));

        final Set<NodeDAO.Capacity> actualOutput3 = NodeDAO.getCapacities(nodeList);
        Assert.assertEquals("Wrong values for nodes.", expectedOutput, actualOutput3);
    }

    @Test
    public void testToCoreUnit() {
        Assert.assertEquals("4", NodeDAO.toCoreUnit("4"));
        Assert.assertEquals(Double.valueOf(0.004D).toString(), NodeDAO.toCoreUnit("4m"));
        Assert.assertEquals(Double.valueOf(0.000000004D).toString(), NodeDAO.toCoreUnit("4n"));
        Assert.assertEquals("<none>", NodeDAO.toCoreUnit(""));
        Assert.assertEquals("<none>", NodeDAO.toCoreUnit(null));
    }
}
