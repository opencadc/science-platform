package org.opencadc.skaha.utils;

import org.junit.Assert;
import org.junit.Test;

public class KubectlCommandBuilderTest {
    @Test
    public void testCommand() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get");
        String[] expectedCommand = {"kubectl", "get"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testNamespace() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").namespace("test");
        String[] expectedCommand = {"kubectl", "get", "-n", "test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testArgument() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").argument("test");
        String[] expectedCommand = {"kubectl", "get", "test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testOutputFormat() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").outputFormat("test");
        String[] expectedCommand = {"kubectl", "get", "-o", "test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testOption() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").option("-f", "value");
        String[] expectedCommand = {"kubectl", "get", "-f", "value"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testPod() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").pod();
        String[] expectedCommand = {"kubectl", "get", "pod"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testJob() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").job();
        String[] expectedCommand = {"kubectl", "get", "job"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testSelector() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").selector("test");
        String[] expectedCommand = {"kubectl", "get", "--selector=test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testLabel() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").label("test");
        String[] expectedCommand = {"kubectl", "get", "-l", "test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testNoHeaders() {
        KubectlCommandBuilder.KubectlCommand actualCommand = KubectlCommandBuilder.command("get").noHeaders();
        String[] expectedCommand = {"kubectl", "get", "--no-headers=true"};

        Assert.assertArrayEquals(expectedCommand, actualCommand.build());
    }

    @Test
    public void testCombination() {
        String[] actualCommand = KubectlCommandBuilder.command("get")
                .pod()
                .namespace( "test")
                .argument("test")
                .outputFormat("json")
                .option("-f", "value")
                .label("test")
                .noHeaders()
                .selector("test")
                .build();

        String[] expectedCommand = {"kubectl", "get", "pod", "-n", "test", "test", "-o", "json", "-f", "value", "-l", "test", "--no-headers=true", "--selector=test"};

        Assert.assertArrayEquals(expectedCommand, actualCommand);
    }
}