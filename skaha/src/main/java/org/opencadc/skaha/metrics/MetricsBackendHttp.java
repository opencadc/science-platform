package org.opencadc.skaha.metrics;

import ca.nrc.cadc.net.HttpGet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared bounded HTTP and Metrics envelope helpers for the Metrics backend DAOs. */
final class MetricsBackendHttp {

    static final int CONNECTION_TIMEOUT_MILLIS = 1_000;
    static final int READ_TIMEOUT_MILLIS = 2_000;
    static final int MAX_RETRIES = 0;
    static final int MAX_RESPONSE_BYTES = 1_048_576;

    private MetricsBackendHttp() {}

    static String fetchUtf8(final URI uri, final String failureDescription) throws Exception {
        final BoundedOutputStream responseBody = new BoundedOutputStream(MAX_RESPONSE_BYTES);
        final HttpGet get = new HttpGet(uri.toURL(), responseBody);
        configureHttpGet(get);
        get.run();
        if (get.getThrowable() != null) {
            throw new IOException(failureDescription + " " + uri, get.getThrowable());
        }
        return responseBody.asUtf8();
    }

    /** Apply a bounded, single-attempt policy to this synchronous optional dashboard read. */
    static void configureHttpGet(final HttpGet get) {
        get.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        get.setReadTimeout(READ_TIMEOUT_MILLIS);
        get.setMaxRetries(MAX_RETRIES);
    }

    static String decodeUtf8(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Metrics response was not valid UTF-8", ex);
        }
    }

    static String text(final JsonObject object, final String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final String text = value.getAsString();
        if (text.isBlank()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        return text;
    }

    static final class BoundedOutputStream extends OutputStream {

        private final int maximumBytes;
        private final ByteArrayOutputStream delegate;
        private int size;

        BoundedOutputStream(final int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.delegate = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        @Override
        public void write(final int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(final int additionalBytes) throws IOException {
            if (additionalBytes > maximumBytes - size) {
                throw new IOException("Metrics response exceeded the maximum size");
            }
            size += additionalBytes;
        }

        private String asUtf8() {
            return decodeUtf8(delegate.toByteArray());
        }
    }
}
