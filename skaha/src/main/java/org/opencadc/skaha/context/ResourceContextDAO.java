package org.opencadc.skaha.context;

import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1LimitRange;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import io.kubernetes.client.openapi.models.V1LimitRangeSpec;
import java.util.Objects;
import org.opencadc.skaha.K8SUtil;

public class ResourceContextDAO {
    private static V1LimitRangeItem LIMIT_RANGE;

    static V1LimitRangeItem getLimitRange(final String sessionLimitRangeName) throws Exception {
        Objects.requireNonNull(sessionLimitRangeName, "Session limit range name cannot be null");
        if (ResourceContextDAO.LIMIT_RANGE == null) {
            final CoreV1Api api = new CoreV1Api(Configuration.getDefaultApiClient());
            final V1LimitRange limitRange = api.readNamespacedLimitRange(
                            sessionLimitRangeName, K8SUtil.getWorkloadNamespace())
                    .execute();
            ResourceContextDAO.LIMIT_RANGE = ResourceContextDAO.getLimitRangeItem(limitRange);
        }

        return ResourceContextDAO.LIMIT_RANGE;
    }

    private static V1LimitRangeItem getLimitRangeItem(V1LimitRange limitRange) {
        Objects.requireNonNull(limitRange, "Limit range cannot be null");
        return Objects.requireNonNullElse(limitRange.getSpec(), new V1LimitRangeSpec()).getLimits().stream()
                .filter(item -> item.getType().equals("Container"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Container limit range item found in limit range: "
                        + (limitRange.getMetadata() == null
                                ? "Unknown"
                                : limitRange.getMetadata().getName())));
    }
}
