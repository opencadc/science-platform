package org.opencadc.skaha.job;

public interface Executor {
    void run() throws Exception;

    void renew() throws Exception;
}
