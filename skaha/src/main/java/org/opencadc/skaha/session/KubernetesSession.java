package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Service;

public class KubernetesSession {
    private final SessionType sessionType;
    private final V1Job job;
    private final V1Ingress ingress;
    private final V1Service service;

    public KubernetesSession(SessionType sessionType, V1Job job, V1Ingress ingress, V1Service service) {
        this.sessionType = sessionType;
        this.job = job;
        this.ingress = ingress;
        this.service = service;
    }

    public SessionType getSessionType() {
        return sessionType;
    }

    public V1Job getJob() {
        return job;
    }

    public V1Ingress getIngress() {
        return ingress;
    }

    public V1Service getService() {
        return service;
    }
}
