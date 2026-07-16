package ink.icoding.codex.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

final class CliVersionProvider implements IVersionProvider {

    private static final String RESOURCE = "/ink/icoding/codex/cli/version.properties";

    @Override
    public String[] getVersion() {
        Properties properties = new Properties();
        try (InputStream input = CliVersionProvider.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new CliException("Missing CLI version resource");
            properties.load(input);
        } catch (IOException exception) {
            throw new CliException("Could not read CLI version", exception);
        }
        return new String[] {"codex-channel " + properties.getProperty("version", "unknown")};
    }
}
