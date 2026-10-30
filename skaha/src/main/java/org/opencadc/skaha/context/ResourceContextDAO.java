package org.opencadc.skaha.context;

import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.LimitRangeList;
import io.fabric8.kubernetes.api.model.LimitRangeSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.util.Objects;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaKubernetesClient;

/** Data Access Object (DAO) for retrieving and caching the Kubernetes LimitRangeItem used for resource context. */
public class ResourceContextDAO {
    private static final Logger LOGGER = Logger.getLogger(ResourceContextDAO.class);
    private static LimitRangeItem CACHED_LIMIT_RANGE_ITEM;

    static LimitRangeItem lookupLimitRange() throws Exception {
        if (ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM == null) {
            final LimitRangeList limitRangeList =
                    SkahaKubernetesClient.get().limitRanges().list();
            Objects.requireNonNull(
                    limitRangeList,
                    "Limit range list cannot be null if " + K8SUtil.SKAHA_SESSION_LIMIT_RANGE_ENABLED_ENV
                            + " feature gate declared.");

            if (limitRangeList.getItems().isEmpty()) {
                throw new IllegalStateException("No limit ranges found in namespace: "
                        + K8SUtil.getWorkloadNamespace()
                        + ". A limit range must be defined if the "
                        + K8SUtil.SKAHA_SESSION_LIMIT_RANGE_ENABLED_ENV
                        + " feature gate is declared.");
            }

            final LimitRange limitRange = limitRangeList.getItems().getFirst();
            ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM = ResourceContextDAO.getLimitRangeItem(limitRange);
            LOGGER.debug("Set cached limit range item to: "
                    + Objects.requireNonNullElse(limitRange.getMetadata(), new ObjectMeta())
                            .getName());
        }

        return ResourceContextDAO.CACHED_LIMIT_RANGE_ITEM;
    }

    private static LimitRangeItem getLimitRangeItem(final LimitRange limitRange) {
        Objects.requireNonNull(limitRange, "Limit range cannot be null");
        return Objects.requireNonNullElse(limitRange.getSpec(), new LimitRangeSpec()).getLimits().stream()
                .filter(item -> item.getType().equals("Container"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Container limit range item found in limit range: "
                        + (limitRange.getMetadata() == null
                                ? "Unknown"
                                : limitRange.getMetadata().getName())));
    }
}
