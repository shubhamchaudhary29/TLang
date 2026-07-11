package dev.tlang.cli;

import java.io.InputStream;
import java.util.Properties;

public final class VersionCommand implements Command {
    @Override
    public void execute(String[] args) {
        String version = "unknown";
        try (InputStream input = VersionCommand.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (input != null) {
                Properties prop = new Properties();
                prop.load(input);
                version = prop.getProperty("version", "unknown");
            }
        } catch (Exception ignored) {
        }
        System.out.println("TLang version " + version);
    }
}
