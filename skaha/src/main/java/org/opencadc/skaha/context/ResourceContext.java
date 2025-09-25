package org.opencadc.skaha.context;

import java.io.IOException;
import java.io.OutputStream;

public interface ResourceContext {
    void write(final OutputStream outputStream) throws IOException;

    default boolean coreOutOfRange(final int coreCount) {
        return getCoreCounts().valueOutside(coreCount);
    }

    default boolean memoryOutOfRange(final int memoryGB) {
        return getMemoryCounts().valueOutside(memoryGB);
    }

    default boolean gpusOutOfRange(final int gpuCount) {
        return getGPUCounts().valueOutside(gpuCount);
    }

    IntegerRange getDefaultCoreCounts();

    IntegerRange getDefaultMemoryCounts();

    IntegerRange getCoreCounts();

    IntegerRange getMemoryCounts();

    IntegerRange getGPUCounts();
}
