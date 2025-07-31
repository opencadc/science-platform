package org.opencadc.skaha.job;

import org.opencadc.skaha.SessionType;

public abstract class Job {
    final String id;
    final String name;
    final String username;
    final String image;
    final boolean enableGPU;
    final JobBuilder.Resources resources;

    Job(final JobBuilder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.image = builder.image;
        this.enableGPU = builder.enableGPU;
        this.resources = builder.resources;
        this.username = builder.username;
    }

    public abstract SessionType getType();

    public void run() {

    }

    public void renew() {

    }
}
