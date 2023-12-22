package org.opencadc.skaha.utils;

import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

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

    public static JSONObject getSecretData(final String secretName, final String secretNamespace) throws Exception {
        // Check the current secret
        final String[] getSecretCommand = new String[] {
                "kubectl", "--namespace", secretNamespace, "get", "--ignore-not-found", "secret",
                secretName, "-o", "jsonpath=\"{.data}\""
        };

        final String data = CommandExecutioner.execute(getSecretCommand);

        // The data from the output begins with a double-quote and ends with one, so strip them.
        return StringUtil.hasText(data) ? new JSONObject(data.replaceFirst("\"", "")
                                                             .substring(0, data.lastIndexOf("\"")))
                                        : new JSONObject();
    }

    protected static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public static String createDirectoryIfNotExist(String... paths) {
        Path path = Paths.get("/", paths);
        File directory = new File(path.toString());
        if (!(directory.exists())) {
            directory.mkdir();
        }
        return path.toString();
    }

    public static String createOrOverrideFile(String directoryPath, String fileName, String content)
            throws IOException {
        Path path = Paths.get(directoryPath, fileName);
        File file = new File(path.toString());
        if (!(file.exists())) {
            file.createNewFile();
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(content + "\n");
        writer.flush();
        writer.close();
        return path.toString();
    }

    public static void changeOwnership(String path, int posixId, int groupId) throws IOException, InterruptedException {
        String[] chown = new String[]{"chown", posixId + ":" + groupId, path};
        execute(chown);
    }
}
