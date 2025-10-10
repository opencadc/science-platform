package org.opencadc.skaha.context;

import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1LimitRange;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import io.kubernetes.client.openapi.models.V1LimitRangeList;
import io.kubernetes.client.openapi.models.V1LimitRangeSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.Objects;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/** Data Access Object (DAO) for retrieving and caching the Kubernetes LimitRangeItem used for resource context. */
public class ResourceContextDAO {
    private static final Logger LOGGER = Logger.getLogger(ResourceContextDAO.class);
    private static V1LimitRangeItem CACHED_LIMIT_RANGE_ITEM;

    static V1LimitRangeItem lookupLimitRange() throws Exception {
        if (ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM == null) {
            final CoreV1Api api = new CoreV1Api(Configuration.getDefaultApiClient());
            final V1LimitRangeList limitRangeList =
                    api.listNamespacedLimitRange(K8SUtil.getWorkloadNamespace()).execute();
            Objects.requireNonNull(
                    limitRangeList,
                    "Limit range list cannot be null if " + ResourceContexts.SESSION_LIMIT_RANGE_FEATURE_GATE
                            + " feature gate declared.");

            if (limitRangeList.getItems().isEmpty()) {
                throw new IllegalStateException("No limit ranges found in namespace: "
                        + K8SUtil.getWorkloadNamespace()
                        + ". A limit range must be defined if the "
                        + ResourceContexts.SESSION_LIMIT_RANGE_FEATURE_GATE
                        + " feature gate is declared.");
            }

            final V1LimitRange limitRange = limitRangeList.getItems().getFirst();
            ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM = ResourceContextDAO.getLimitRangeItem(limitRange);
            LOGGER.debug("Set cached limit range item to: "
                    + Objects.requireNonNullElse(limitRange.getMetadata(), new V1ObjectMeta())
                            .getName());
        }

        return ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM;
    }

    private static V1LimitRangeItem getLimitRangeItem(final V1LimitRange limitRange) {
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
