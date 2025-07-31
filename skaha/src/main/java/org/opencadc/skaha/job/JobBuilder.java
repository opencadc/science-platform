package org.opencadc.skaha.job;

import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import org.opencadc.skaha.context.ResourceContexts;

public abstract class JobBuilder {
    static final String NAME_PARAMETER = "name";
    static final String IMAGE_PARAMETER = "image";
    static final String CORES_PARAMETER = "cores";
    static final String MEMORY_PARAMETER = "ram";
    static final String GPU_PARAMETER = "gpu";

    String id;
    String name;
    String username;
    String image;
    boolean enableGPU = false;
    Resources resources;
    Executor executor;

    public JobBuilder() {
        this.id = new RandomStringGenerator(8).getID();
    }

    public <T extends JobBuilder> T withRequest(final SyncInput syncInput, final ResourceContexts resourceContexts) {
        this.name = syncInput.getParameter(JobBuilder.NAME_PARAMETER);
        this.image = syncInput.getParameter(JobBuilder.IMAGE_PARAMETER);

        final String coreCountString = syncInput.getParameter(JobBuilder.CORES_PARAMETER);
        final int coresRequested;
        final int coresLimit;
        if (coreCountString != null) {
            try {
                coresRequested = Integer.parseInt(coreCountString);
                coresLimit = coresRequested;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid core count: " + coreCountString, e);
            }
        } else {
            coresRequested = resourceContexts.getDefaultRequestCores(); // Default value
            coresLimit = resourceContexts.getDefaultLimitCores(); // Default value
        }

        final String memoryString = syncInput.getParameter(JobBuilder.MEMORY_PARAMETER);
        final int memoryInGBRequested;
        final int memoryInGBLimit;
        if (memoryString != null) {
            try {
                memoryInGBRequested = Integer.parseInt(memoryString);
                memoryInGBLimit = memoryInGBRequested; // Assuming limit is the same as request
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid core count: " + coreCountString, e);
            }
        } else {
            memoryInGBRequested = resourceContexts.getDefaultRequestRAM(); // Default value
            memoryInGBLimit = resourceContexts.getDefaultLimitRAM(); // Default value
        }

        final String gpuString = syncInput.getParameter(JobBuilder.GPU_PARAMETER);
        if (StringUtil.hasText(gpuString)) {
            this.enableGPU = Boolean.parseBoolean(gpuString) || gpuString.equalsIgnoreCase("1");
        }

        this.resources = new Resources(coresRequested, coresLimit, memoryInGBRequested, memoryInGBLimit);

        return (T) this;
    }

    public <T extends JobBuilder> T withExecutor(final Executor executor) {
        this.executor = executor;
        return (T) this;
    }

    public <T extends JobBuilder> T withUsername(final String username) {
        this.username = username;
        return (T) this;
    }

    static class Resources {
        final int coresRequested;
        final int coresLimit;
        final int memoryRequestedInGB;
        final int memoryLimitInGB;

        public Resources(int coresRequested, int coresLimit, int memoryRequestedInGB, int memoryLimitInGB) {
            this.coresRequested = coresRequested;
            this.coresLimit = coresLimit;
            this.memoryRequestedInGB = memoryRequestedInGB;
            this.memoryLimitInGB = memoryLimitInGB;
        }
    }
}
