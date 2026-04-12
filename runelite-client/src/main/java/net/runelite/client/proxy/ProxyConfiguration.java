package net.runelite.client.proxy;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.runelite.client.plugins.microbot.Microbot;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Configures the JVM to use a SOCKS5 proxy if the appropriate command line argument is provided.
 */
public class ProxyConfiguration {

    /**
     * Sets up the proxy configuration based on the provided options.
     * If the --proxy CLI arg is absent, falls back to reading proxy=<url>
     * from credentials.properties inside the Command Center profile dir (when
     * --cc-profile-dir is set). This keeps proxy credentials out of argv,
     * which is readable by any local user via /proc/&lt;pid&gt;/cmdline or Task
     * Manager.
     *
     * @return the resolved proxy URL if a proxy was configured, or null.
     */
    public static String setupProxy(OptionSet options, ArgumentAcceptingOptionSpec<String> proxyInfo) {
        String proxyUrl = null;
        if (options.has(proxyInfo)) {
            proxyUrl = options.valueOf(proxyInfo);
        } else {
            // Fallback: the Command Center writes proxy=<url> into
            // credentials.properties so creds never hit argv. cc-profile-dir
            // is set as a system property by the RuneLite entry point
            // immediately before this call.
            String ccProfileDir = System.getProperty("cc-profile-dir");
            if (ccProfileDir != null && !ccProfileDir.isEmpty()) {
                proxyUrl = readProxyFromCredentials(ccProfileDir);
            }
        }

        if (proxyUrl == null || proxyUrl.isEmpty()) {
            return null;
        }

        if (options.has("proxy-type")) {
            Microbot.showMessage("Proxy type is no longer supported, please use the format -proxy=socks://user:pass@host:port or http://user:pass@host:port");
            System.exit(1);
        }

        URI uri = URI.create(proxyUrl);

        String host = uri.getHost();
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);

        validateProxyScheme(scheme);

        int port = validatePort(uri.getPort());

        String[] credentials = extractCredentials(uri);
        String user = credentials[0];
        String pass = credentials[1];

        configureProxy(host, port);

        if (user != null) {
            setupAuthenticator(user, pass);
        }

        return proxyUrl;
    }

    /**
     * Reads the proxy=&lt;url&gt; line from the credentials.properties file in
     * the given Command Center profile directory. Returns null if the file or
     * the key is absent. Errors are silently ignored — the CLI fallback will
     * handle reporting.
     */
    private static String readProxyFromCredentials(String ccProfileDir) {
        if (ccProfileDir == null || ccProfileDir.isEmpty()) {
            return null;
        }
        Path credentialsFile = Path.of(ccProfileDir, "credentials.properties");
        if (!Files.exists(credentialsFile)) {
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(credentialsFile)) {
            props.load(reader);
        } catch (IOException e) {
            return null;
        }
        String proxy = props.getProperty("proxy");
        return (proxy == null || proxy.isEmpty()) ? null : proxy;
    }

    /**
     * Validates the proxy scheme to ensure it is SOCKS5.
     * @param scheme
     */
    private static void validateProxyScheme(String scheme) {
        boolean isHttpProxy = scheme.equals("http") || scheme.equals("https");
        if (isHttpProxy) {
            Microbot.showMessage("HTTP(S) proxies are not supported, please use a SOCKS5 proxy. \n\n This is to make sure that osrs traffic is also routed through the proxy.");
            System.exit(1);
        }

        boolean isSocksProxy = scheme.equals("socks") || scheme.equals("socks5");
        if (!isSocksProxy) {
            Microbot.showMessage("Proxy scheme must be socks(5).");
            System.exit(1);
        }
    }

    /**
     * Validates the proxy port to ensure it is a positive integer.
     * @param port
     * @return
     */
    private static int validatePort(int port) {
        if (port <= 0) {
            Microbot.showMessage("Invalid proxy port");
            System.exit(1);
        }
        return port;
    }

    /**
     * Extracts the username and password from the URI's user info.
     * @param uri
     * @return
     */
    private static String[] extractCredentials(URI uri) {
        String user = null;
        String pass = null;
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            user = userInfo[0];
            pass = userInfo[1];
        }
        return new String[]{user, pass};
    }

    /**
     * Configures the JVM to use the specified SOCKS5 proxy.
     * @param host
     * @param port
     */
    private static void configureProxy(String host, int port) {
        System.setProperty("socksProxyHost", host);
        System.setProperty("socksProxyPort", String.valueOf(port > 0 ? port : 1080));
    }

    /**
     * Sets up the default authenticator for proxy authentication.
     * @param user
     * @param pass
     */
    private static void setupAuthenticator(String user, String pass) {
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass != null ? pass.toCharArray() : new char[0]);
            }
        });
    }
}