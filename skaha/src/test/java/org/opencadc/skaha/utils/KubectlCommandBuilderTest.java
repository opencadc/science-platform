package org.opencadc.skaha.utils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.opencadc.skaha.utils.KubectlCommandBuilder.KubectlCommand;

@RunWith(MockitoJUnitRunner.class)
public class KubectlCommandBuilderTest {

    @Test
    public void testCommand() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation);
        String[] expectedCommand = {"kubectl", operation};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testNamespace() {
        String operation = "get";
        String namespace = "test-namespace";
        KubectlCommand command = KubectlCommandBuilder.command(operation).namespace(namespace);
        String[] expectedCommand = {"kubectl", operation, "--namespace", namespace};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testArgument() {
        String operation = "get";
        String argument = "pods";
        KubectlCommand command = KubectlCommandBuilder.command(operation).argument(argument);
        String[] expectedCommand = {"kubectl", operation, argument};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testOutputFormat() {
        String operation = "get";
        String format = "json";
        KubectlCommand command = KubectlCommandBuilder.command(operation).outputFormat(format);
        String[] expectedCommand = {"kubectl", operation, "-o", format};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testJson() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation).json();
        String[] expectedCommand = {"kubectl", operation, "-o", "json"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testOption() {
        String operation = "get";
        String option = "--label";
        String value = "app=myapp";
        KubectlCommand command = KubectlCommandBuilder.command(operation).option(option, value);
        String[] expectedCommand = {"kubectl", operation, option, value};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testPod() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation).pod();
        String[] expectedCommand = {"kubectl", operation, "pod"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testJob() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation).job();
        String[] expectedCommand = {"kubectl", operation, "job"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testPvc() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation).pvc();
        String[] expectedCommand = {"kubectl", operation, "pvc"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testLabel() {
        String operation = "get";
        String label = "app=myapp";
        KubectlCommand command = KubectlCommandBuilder.command(operation).label(label);
        String[] expectedCommand = {"kubectl", operation, "-l", label};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testBuild() {
        String operation = "get";
        String namespace = "test-namespace";
        String argument = "pods";
        KubectlCommand command =
                KubectlCommandBuilder.command(operation).namespace(namespace).argument(argument);
        String[] expectedCommand = {"kubectl", operation, "--namespace", namespace, argument};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testBuildWithMultipleOptions() {
        String operation = "get";
        String namespace = "test-namespace";
        String argument = "pods";
        String label = "app=myapp";
        KubectlCommand command = KubectlCommandBuilder.command(operation)
                .namespace(namespace)
                .argument(argument)
                .label(label);
        String[] expectedCommand = {"kubectl", operation, "--namespace", namespace, argument, "-l", label};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testBuildWithJsonOutput() {
        String operation = "get";
        String namespace = "test-namespace";
        String argument = "pods";
        KubectlCommand command = KubectlCommandBuilder.command(operation)
                .namespace(namespace)
                .argument(argument)
                .json();
        String[] expectedCommand = {"kubectl", operation, "--namespace", namespace, argument, "-o", "json"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testNoHeaders() {
        String operation = "get";
        KubectlCommand command = KubectlCommandBuilder.command(operation).noHeaders();
        String[] expectedCommand = {"kubectl", operation, "--no-headers=true"};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testSelector() {
        String operation = "get";
        String selector = "env=prod";
        KubectlCommand command = KubectlCommandBuilder.command(operation).selector(selector);
        String[] expectedCommand = {"kubectl", operation, "--selector=" + selector};
        Assert.assertArrayEquals(expectedCommand, command.build());
    }

    @Test
    public void testMultipleChainedMethods() {
        String operation = "get";
        String namespace = "test-namespace";
        String argument = "pods";
        String label = "app=myapp";
        String selector = "env=prod";
        KubectlCommand command = KubectlCommandBuilder.command(operation)
                .namespace(namespace)
                .argument(argument)
                .label(label)
                .selector(selector)
                .noHeaders()
                .json();
        String[] expectedCommand = {
            "kubectl",
            operation,
            "--namespace",
            namespace,
            argument,
            "-l",
            label,
            "--selector=" + selector,
            "--no-headers=true",
            "-o",
            "json"
        };
        Assert.assertArrayEquals(expectedCommand, command.build());
    }
}
