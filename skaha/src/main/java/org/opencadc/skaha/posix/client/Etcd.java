package org.opencadc.skaha.posix.client;

import ca.nrc.cadc.util.MultiValuedProperties;
import ca.nrc.cadc.util.PropertiesReader;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.PutOption;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static io.etcd.jetcd.ByteSequence.from;
import static java.util.Optional.empty;
import static java.util.Optional.of;

public class Etcd {

    private static final Logger log = Logger.getLogger(Etcd.class);

    public static final String CADC_REGISTRY_PROPERTIES = "cadc-registry.properties";
    private final Client client;

    public Etcd() {
        this(fetchFromPropertiesFile());
    }

    public Etcd(List<String> urls) {
        client = Client.builder().endpoints(buildUrls(urls)).build();
    }

    public Etcd(String url, String... peerUrls) {
        client = Client.builder().endpoints(buildUrls(url, peerUrls)).build();
    }

    private static List<String> fetchFromPropertiesFile() {
        PropertiesReader propReader = new PropertiesReader(CADC_REGISTRY_PROPERTIES);
        MultiValuedProperties mvp = propReader.getAllProperties();
        List<String> urls = mvp.getProperty("posix.database.url");
        if (urls.isEmpty())
            throw new RuntimeException("posix database url is not present");
        log.debug("etcd url " + urls);
        return urls;
    }

    private String[] buildUrls(List<String> peerUrls) {
        String[] urls = new String[peerUrls.size()];
        int index = 0;
        for (String peerUrl : peerUrls)
            urls[index++] = peerUrl;

        return urls;
    }

    private String[] buildUrls(String url, String... peerUrls) {
        String[] urls = new String[1 + peerUrls.length];
        int index = 0;
        urls[index++] = url;
        for (String peerUrl : peerUrls)
            urls[index++] = peerUrl;
        return urls;
    }

    public void put(String key, String value) throws ExecutionException, InterruptedException {
        KV kvClient = client.getKVClient();
        ByteSequence keySeq = from(key.getBytes());
        ByteSequence valueSeq = from(value.getBytes());
        kvClient.put(keySeq, valueSeq, PutOption.builder().build()).get();
    }

    public boolean exists(String key) throws ExecutionException, InterruptedException {
        return !getKeyValues(key).isEmpty();
    }

    public boolean delete(String key) throws ExecutionException, InterruptedException {
        if (!exists(key))
            return false;
        KV kvClient = client.getKVClient();
        ByteSequence keySeq = from(key.getBytes());
        kvClient.delete(keySeq).get();
        return true;
    }

    public Optional<String> get(String key) throws ExecutionException, InterruptedException {
        List<KeyValue> keyValues = getKeyValues(key);
        if (keyValues.isEmpty())
            return empty();
        return of(keyValues.get(0).getValue().toString());
    }

    private List<KeyValue> getKeyValues(String key) throws InterruptedException, ExecutionException {
        KV kvClient = client.getKVClient();
        ByteSequence keySeq = from(key.getBytes());
        CompletableFuture<GetResponse> getFuture = kvClient.get(keySeq);
        GetResponse response = getFuture.get();
        return response.getKvs();
    }

    public void put(String key, List<String> values) throws ExecutionException, InterruptedException, IOException {
        KV kvClient = client.getKVClient();
        ByteSequence keySeq = from(key.getBytes());
        ByteSequence valueSeq = from(serializeList(values));
        kvClient.put(keySeq, valueSeq, PutOption.builder().build()).get();
    }

    public Optional<List<String>> getAsList(String key) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        List<KeyValue> keyValues = getKeyValues(key);
        if (keyValues.isEmpty())
            return empty();
        return of(deserializeList(keyValues.get(0).getValue().getBytes()));

    }

    private byte[] serializeList(List<String> list) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(list);
        objectOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private List<String> deserializeList(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        List<String> list = (List<String>) objectInputStream.readObject();
        objectInputStream.close();
        return list;
    }

}


