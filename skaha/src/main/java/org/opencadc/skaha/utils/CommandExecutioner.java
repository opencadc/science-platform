package org.opencadc.skaha.utils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.log4j.Logger;

public class CommandExecutioner {
    private static final Logger log = Logger.getLogger(CommandExecutioner.class);

    public static String execute(String[] command) throws IOException, InterruptedException {
        return execute(command, true);
    }

    public static String execute(String[] command, boolean allowError) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);
        String stdout = readStream(p.getInputStream());
        String stderr = readStream(p.getErrorStream());
        log.debug("stdout: " + stdout);
        log.debug("stderr: " + stderr);
        int status = p.waitFor();
        log.debug("Status=" + status + " for command: " + Arrays.toString(command));
        if (status != 0) {
            if (allowError) {
                return stderr;
            } else {
                String message = "Error executing command: " + Arrays.toString(command) + " Error: " + stderr;
                throw new IOException(message);
            }
        }
        return stdout.trim();
    }

    public static void execute(String[] command, OutputStream out) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);

        WritableByteChannel wbc = Channels.newChannel(out);
        ReadableByteChannel rbc = Channels.newChannel(p.getInputStream());

        int count = 0;
        ByteBuffer buffer = ByteBuffer.allocate(512);
        while (count != -1) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            count = rbc.read(buffer);
            if (count != -1) {
                wbc.write(buffer.flip());
                buffer.flip();
            }
        }
    }

    public static void execute(final String[] command, final OutputStream standardOut, final OutputStream standardErr)
            throws IOException, InterruptedException {
        final Process p = Runtime.getRuntime().exec(command);
        final int code = p.waitFor();
        try (final InputStream stdOut = new BufferedInputStream(p.getInputStream());
                final InputStream stdErr = new BufferedInputStream(p.getErrorStream())) {
            final String commandOutput = readStream(stdOut);
            if (code != 0) {
                final String errorOutput = readStream(stdErr);
                log.error("Code (" + code + ") found from command " + Arrays.toString(command));
                log.error(errorOutput);
                standardErr.write(errorOutput.getBytes());
                standardErr.flush();
            } else {
                log.debug(commandOutput);
                log.debug("Executing " + Arrays.toString(command) + ": OK");
            }
            standardOut.write(commandOutput.getBytes());
            standardOut.flush();
        }
    }

    protected static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public static void changeOwnership(String path, int posixId, int groupId) throws IOException, InterruptedException {
        String[] chown = new String[] {"chown", posixId + ":" + groupId, path};
        CommandExecutioner.execute(chown);
    }
}
