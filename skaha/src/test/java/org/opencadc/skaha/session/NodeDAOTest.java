package org.opencadc.skaha.session;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1NodeStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class NodeDAOTest {
    @Test
    public void testGetAvailableResources() {
        final V1NodeList nodeList = new V1NodeList();
        Assert.assertEquals("Wrong output.", Collections.emptyMap(), NodeDAO.getAvailableResources(nodeList));

        final V1Node node1 = new V1Node();
        final Map<String, Quantity> node1Capacity = new HashMap<>();
        node1Capacity.put("cpu", new Quantity("4"));
        final V1NodeStatus node1Status = new V1NodeStatus().capacity(node1Capacity);
        node1.metadata(new V1ObjectMeta().name("node1")).status(node1Status);
        nodeList.addItemsItem(node1);

        final Map<String, String[]> expectedOutput1 = new HashMap<>();
        expectedOutput1.put("node1", new String[] {"4", "0", "0"});

        final Map<String, String[]> actualOutput1 = NodeDAO.getAvailableResources(nodeList);
        Assert.assertEquals("Wrong key count.", 1, expectedOutput1.size());
        Assert.assertEquals("Wrong keys.", expectedOutput1.keySet(), actualOutput1.keySet());
        Assert.assertArrayEquals("Wrong values for node1.", expectedOutput1.get("node1"), actualOutput1.get("node1"));

        // Add memory to node1
        node1Capacity.put("memory", new Quantity("28Gi"));
        final Map<String, String[]> expectedOutput2 = new HashMap<>();
        expectedOutput2.put("node1", new String[] {"4", "28", "0"});

        final Map<String, String[]> actualOutput2 = NodeDAO.getAvailableResources(nodeList);
        Assert.assertEquals("Wrong key count.", 1, expectedOutput2.size());
        Assert.assertEquals("Wrong keys.", expectedOutput2.keySet(), actualOutput2.keySet());
        Assert.assertArrayEquals("Wrong values for node1.", expectedOutput1.get("node1"), actualOutput1.get("node1"));

        // Add node2
        final V1Node node2 = new V1Node();
        final Map<String, Quantity> node2Capacity = new HashMap<>();
        node2Capacity.put("cpu", new Quantity("16"));
        node2Capacity.put("memory", new Quantity("168Gi"));
        node2Capacity.put("nvidia.com/gpu.count", new Quantity("3"));
        final V1NodeStatus node2Status = new V1NodeStatus().capacity(node2Capacity);
        node2.metadata(new V1ObjectMeta().name("node2")).status(node2Status);
        nodeList.addItemsItem(node2);

        final Map<String, String[]> expectedOutput3 = new HashMap<>();
        expectedOutput3.put("node1", new String[] {"4", "28", "0"});
        expectedOutput3.put("node2", new String[] {"16", "168", "3"});

        final Map<String, String[]> actualOutput3 = NodeDAO.getAvailableResources(nodeList);
        Assert.assertEquals("Wrong key count.", 2, expectedOutput3.size());
        Assert.assertEquals("Wrong keys.", new HashSet<>(Arrays.asList("node1", "node2")), expectedOutput3.keySet());
        Assert.assertEquals("Wrong keys.", expectedOutput3.keySet(), actualOutput3.keySet());

        Assert.assertArrayEquals("Wrong values for node1.", expectedOutput1.get("node1"), actualOutput1.get("node1"));
        Assert.assertArrayEquals("Wrong values for node2.", expectedOutput1.get("node2"), actualOutput1.get("node2"));
    }
}
