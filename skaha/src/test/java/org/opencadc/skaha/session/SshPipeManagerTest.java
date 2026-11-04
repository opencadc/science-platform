package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

public class SshPipeManagerTest {
    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void createPipeUsesUsernameAndJobOwnerReference() throws Exception {
        this.api
                .server()
                .expect()
                .post()
                .withPath("/apis/sshpiper.com/v1beta1/namespaces/" + NAMESPACE + "/pipes")
                .andReturn(201, pipeResponse())
                .once();

        final Job job = new JobBuilder()
                .withNewMetadata()
                .withName("skaha-ssh-alice-abc123")
                .withUid("job-uid-1")
                .endMetadata()
                .build();

        SshPipeManager.createForSession(job, "alice", "abc123", "skaha-ssh-pipe-upstream-abc123");

        final List<String> requests = this.api.requests();
        Assert.assertTrue(requests.stream().anyMatch(line -> line.startsWith("POST") && line.contains("/pipes")));
    }

    private static String pipeResponse() {
        return """
                {
                  "apiVersion": "sshpiper.com/v1beta1",
                  "kind": "Pipe",
                  "metadata": {"name": "skaha-ssh-alice", "namespace": "%s"},
                  "spec": {}
                }
                """
                .formatted(NAMESPACE);
    }
}
