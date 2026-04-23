package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1Job;

public interface SessionProfile {
    void configure(final V1Job job, final SessionContext context, final SessionProfileConfiguration sessionProfileConfiguration);
}
