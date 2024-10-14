package org.opencadc.skaha.utils;

public class Resource {
    private float cpu;
    private float ephemeralStorage;
    private float memory;

    public Resource() {
    }

    public Resource(float cpu, float ephemeralStorage, float memory) {
        this.cpu = cpu;
        this.ephemeralStorage = ephemeralStorage;
        this.memory = memory;
    }

    public float cpu() {
        return cpu;
    }

    public void setCpu(float cpu) {
        this.cpu = cpu;
    }

    public void setEphemeralStorage(float ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }

    public void setMemory(float memory) {
        this.memory = memory;
    }

    public float ephemeralStorage() {
        return ephemeralStorage;
    }

    public float memory() {
        return memory;
    }

    public Resource add(Resource another) {
        System.out.println(another.cpu + "thsi is cpu");
        this.cpu += another.cpu;
        this.memory += another.memory;
        this.ephemeralStorage = another.ephemeralStorage;
        return this;
    }
}
