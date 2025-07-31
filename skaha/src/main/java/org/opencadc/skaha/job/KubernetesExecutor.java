package org.opencadc.skaha.job;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.Config;

public class KubernetesExecutor implements Executor {
    static final String SESSION_ID_LABEL = "org.opencadc.science-platform/session-id";
    static final String SESSION_TYPE_LABEL = "org.opencadc.science-platform/session-type";
    static final String DESKTOP_APP_ID_LABEL = "org.opencadc.science-platform/desktop-app-id";
    static final String USER_ID_LABEL = "org.opencadc.science-platform/user-id";
    static final String JOB_NAME_LABEL = "org.opencadc.science-platform/job-name";

    public static final int DEFAULT_TIME_TO_LIVE_SECONDS = 60 * 60 * 24; // 1 day

    private int timeToLiveAfterFinishedSeconds;
    private long activeDeadlineSeconds;
    private String hostname;
    private String imagePullPolicy;
    private String priorityClassName;

    KubernetesExecutor(final KubernetesJob kubernetesJob) {
        // Default constructor
    }

    @Override
    public void run() throws Exception {
        final ApiClient client = Config.fromCluster();
        Configuration.setDefaultApiClient(client);

    }

    @Override
    public void renew() throws Exception {
        final ApiClient client = Config.fromCluster();
        Configuration.setDefaultApiClient(client);
    }
}
