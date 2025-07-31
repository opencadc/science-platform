package org.opencadc.skaha.job;

public class ExecutorFactory {
    public static Executor withEngine(ExecutorEngine engine) {
        switch (engine) {
            case KUBERNETES:
                return new KubernetesExecutor();
            default:
                throw new IllegalArgumentException("Unsupported executor engine: " + engine);
        }
    }
}
