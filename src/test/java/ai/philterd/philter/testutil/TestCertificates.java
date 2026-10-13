/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.testutil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Keystores for TLS tests, made with the JDK's keytool as the Docker entrypoint makes Philter's certificate: a server
 * certificate for localhost, a CA, a client certificate the CA issued, and a self-signed client certificate no one
 * trusts. Every store is PKCS12 with the password {@link #PASSWORD}.
 */
public final class TestCertificates {

    public static final String PASSWORD = "changeit";

    /** Philter's keystore: its certificate for localhost, under the alias {@code philter}. */
    public final Path serverKeyStore;
    /** A trust store holding Philter's certificate, for clients. */
    public final Path serverTrustStore;
    /** A trust store holding the CA, for Philter. */
    public final Path caTrustStore;
    /** A client certificate the CA issued, with its key. */
    public final Path clientKeyStore;
    /** A self-signed client certificate, with its key, that the CA did not issue. */
    public final Path untrustedClientKeyStore;

    private final Path directory;

    private TestCertificates(final Path directory) {
        this.directory = directory;
        this.serverKeyStore = directory.resolve("server.p12");
        this.serverTrustStore = directory.resolve("server-trust.p12");
        this.caTrustStore = directory.resolve("ca-trust.p12");
        this.clientKeyStore = directory.resolve("client.p12");
        this.untrustedClientKeyStore = directory.resolve("untrusted-client.p12");
    }

    /** Creates the keystores in a new temporary directory. */
    public static TestCertificates create() {
        try {
            final TestCertificates certificates = new TestCertificates(Files.createTempDirectory("philter-tls"));
            certificates.generate();
            return certificates;
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void generate() throws IOException {
        final Path ca = directory.resolve("ca.p12");
        final Path caCertificate = directory.resolve("ca.crt");
        final Path serverCertificate = directory.resolve("server.crt");
        final Path clientRequest = directory.resolve("client.csr");
        final Path clientCertificate = directory.resolve("client.crt");

        genkeypair(serverKeyStore, "philter", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
        keytool("-exportcert", "-rfc", "-alias", "philter", "-keystore", serverKeyStore, "-file", serverCertificate);
        keytool("-importcert", "-noprompt", "-alias", "philter", "-file", serverCertificate, "-keystore", serverTrustStore);

        genkeypair(ca, "ca", "CN=Test Client CA", "-ext", "BasicConstraints:critical=ca:true");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", ca, "-file", caCertificate);
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCertificate, "-keystore", caTrustStore);

        genkeypair(clientKeyStore, "client", "CN=test-client");
        keytool("-certreq", "-alias", "client", "-keystore", clientKeyStore, "-file", clientRequest);
        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", ca, "-infile", clientRequest, "-outfile", clientCertificate,
                "-ext", "ExtendedKeyUsage=clientAuth");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCertificate, "-keystore", clientKeyStore);
        keytool("-importcert", "-noprompt", "-alias", "client", "-file", clientCertificate, "-keystore", clientKeyStore);

        genkeypair(untrustedClientKeyStore, "client", "CN=untrusted-client");
    }

    private static void genkeypair(final Path keyStore, final String alias, final String name, final Object... extra)
            throws IOException {
        final List<Object> arguments = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", name, "-keystore", keyStore));
        arguments.addAll(List.of(extra));
        keytool(arguments.toArray());
    }

    private static void keytool(final Object... arguments) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        for (final Object argument : arguments) {
            command.add(argument.toString());
        }
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            final String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IOException("keytool failed: " + String.join(" ", command) + "\n" + output);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }

    /** A client's TLS context: trusting Philter's certificate, and presenting {@code clientKeyStore}'s if not null. */
    public SSLContext clientContext(final Path clientKeyStore) {
        try {
            final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load(serverTrustStore));
            final SSLContext context = SSLContext.getInstance("TLS");
            if (clientKeyStore == null) {
                context.init(null, trust.getTrustManagers(), null);
            } else {
                final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(clientKeyStore), PASSWORD.toCharArray());
                context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            }
            return context;
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static KeyStore load(final Path path) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

}
