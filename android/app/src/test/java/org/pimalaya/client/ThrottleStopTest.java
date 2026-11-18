package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * A stop cuts a throttled server's wait short.
 *
 * <p>The app stops a sync by interrupting its thread (the job's stop, the
 * app closing). A CardDAV server on the loopback answers every request 503
 * with a {@code Retry-After} of 20 s, which the bridge waits out before it
 * sends the request again; the calling thread is interrupted during the wait.
 * The server speaks TLS, as the bridge refuses a credential over plain http,
 * under a certificate made for the run and trusted for it alone, through the
 * JVM's own TLS (Conscrypt cannot reach the JVM's sockets here).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ThrottleStopTest {
    private static final byte[] BUSY =
            ("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 20\r\n"
                            + "Content-Length: 0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
    private static final char[] SECRET = "secret".toCharArray();

    private Path dir;
    private SSLContext before;
    private HostnameVerifier verifier;
    private SSLServerSocket server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("throttle-stop");
        Path keys = dir.resolve("keys.p12");
        Process keytool =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "loopback",
                                "-keyalg",
                                "EC",
                                "-groupname",
                                "secp256r1",
                                "-dname",
                                "CN=127.0.0.1",
                                "-ext",
                                "SAN=ip:127.0.0.1",
                                "-validity",
                                "1",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keys.toString(),
                                "-storepass",
                                "secret",
                                "-keypass",
                                "secret")
                        .redirectErrorStream(true)
                        .start();
        keytool.getInputStream().readAllBytes();
        assertEquals("keytool", 0, keytool.waitFor());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(keys.toFile())) {
            store.load(input, SECRET);
        }
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("loopback", store.getCertificate("loopback"));

        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, SECRET);
        SSLContext serving = SSLContext.getInstance("TLS");
        serving.init(keyManagers.getKeyManagers(), null, null);

        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trustManagers.getTrustManagers(), null);

        // NOTE: the transport takes the platform's defaults; the JVM's
        // default hostname verifier answers false to every name.
        before = SSLContext.getDefault();
        verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLContext.setDefault(client);
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> host.equals("127.0.0.1"));

        server =
                (SSLServerSocket)
                        serving.getServerSocketFactory()
                                .createServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread accepting = new Thread(this::serve);
        accepting.setDaemon(true);
        accepting.start();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        SSLContext.setDefault(before);
        HttpsURLConnection.setDefaultHostnameVerifier(verifier);
        Thread.interrupted();
        try (var files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.delete(file);
            }
        }
        Files.delete(dir);
    }

    /** Answers every request of every connection 503, to stay busy. */
    private void serve() {
        try {
            while (true) {
                Socket socket = server.accept();
                Thread connection = new Thread(() -> answer(socket));
                connection.setDaemon(true);
                connection.start();
            }
        } catch (IOException closed) {
            // NOTE: the test is over.
        }
    }

    private void answer(Socket socket) {
        try (Socket held = socket) {
            InputStream input = held.getInputStream();
            OutputStream output = held.getOutputStream();
            String line;
            while ((line = readRequest(input)) != null) {
                requests.add(line);
                output.write(BUSY);
                output.flush();
            }
        } catch (IOException gone) {
            // NOTE: the client went first.
        }
    }

    /**
     * Reads one request, head and body, answering its request line, null at
     * the end of the stream.
     */
    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (true) {
            int read = input.read();
            if (read < 0) {
                return null;
            }
            head.write(read);
            String text = head.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n\r\n")) {
                String[] lines = text.split("\r\n");
                for (String line : lines) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        int length = Integer.parseInt(line.substring(15).trim());
                        input.readNBytes(length);
                    }
                }
                return lines[0];
            }
        }
    }

    /**
     * Lists the account's books while {@code stop} runs a second in, and
     * answers how long the call took.
     */
    private long listedStopping(Runnable stop) throws Exception {
        Account account =
                new Account("https://127.0.0.1:" + server.getLocalPort() + "/", "u", "p");
        Thread stopper =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(1_000);
                                stop.run();
                            } catch (InterruptedException ignored) {
                                // NOTE: the test ended first.
                            }
                        });
        stopper.setDaemon(true);

        long started = System.nanoTime();
        stopper.start();
        try (Transport transport = new Transport()) {
            PimalayaException busy =
                    assertThrows(
                            PimalayaException.class,
                            () -> new ContactClient().listAddressbooks(transport, account));
            assertTrue(busy.getMessage(), busy.getMessage().contains("503"));
        }
        long waited = (System.nanoTime() - started) / 1_000_000;
        stopper.join();

        // NOTE: the discovery tries its next address, which the kept stop
        // gives up at once too: no request is sent twice.
        assertEquals(
                "no request sent again: " + requests,
                new HashSet<>(requests).size(),
                requests.size());
        return waited;
    }

    /** The app closing interrupts the thread waiting. */
    @Test
    public void anInterruptCutsAThrottledWaitShort() throws Exception {
        Thread caller = Thread.currentThread();

        long waited = listedStopping(caller::interrupt);

        assertTrue("waited " + waited + " ms of a 20 s wait", waited < 10_000);
        assertTrue("the stop is kept for the caller", Thread.interrupted());
    }

    /**
     * The background job told to stop cuts the waits of every thread, until
     * its run ends.
     */
    @Test
    public void aStoppedJobCutsAThrottledWaitShort() throws Exception {
        long waited;
        try {
            waited = listedStopping(Transport::stopWaits);
        } finally {
            Transport.allowWaits();
        }

        assertTrue("waited " + waited + " ms of a 20 s wait", waited < 10_000);
        try (Transport transport = new Transport()) {
            long started = System.nanoTime();
            assertTrue("waits run again", transport.pause(50));
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 50);
        }
    }
}
