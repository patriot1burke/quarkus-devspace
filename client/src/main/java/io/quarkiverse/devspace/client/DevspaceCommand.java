package io.quarkiverse.devspace.client;

import java.util.concurrent.Callable;

import jakarta.inject.Inject;

import io.quarkus.devspace.client.DevspaceConnectionConfig;
import picocli.CommandLine;

@CommandLine.Command(name = "devspace")
public class DevspaceCommand implements Callable<Integer> {

    @CommandLine.Option(names = { "-p",
            "--port" }, defaultValue = "8080", description = "port of local process", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    private int port = 8080;

    @CommandLine.Parameters(index = "0", description = "URI of devspace")
    private String uri;

    @Inject
    Client client;

    @Override
    public Integer call() throws Exception {
        DevspaceConnectionConfig config = DevspaceConnectionConfig.fromUri(uri);
        if (config.error != null) {
            System.out.println(config.error);
            System.out.println();
            new CommandLine(new DevspaceCommand()).usage(System.out);
            return CommandLine.ExitCode.USAGE;
        }
        if (!client.start(port, config)) {
            System.out.println("Failed to start");
        }

        return 0;
    }
}
