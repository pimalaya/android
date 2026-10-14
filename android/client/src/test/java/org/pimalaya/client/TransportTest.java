package org.pimalaya.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The transport drops a connection that died under it.
 *
 * <p>A server answering once and closing, one resetting, an answer never
 * settled. A local server answers one request per connection, then does what
 * the test asks.
 */
public class TransportTest {
    private static final byte[] REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ANSWER =
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);

    /** What a connection does once it answered its first request. */
    private enum Then {
        CLOSE,
        RESET,
        STAY
    }

    private ServerSocket server;
    private Thread serving;
    private String url;
    private Transport transport;
    private final BlockingQueue<Then> script = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> seen = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = new ServerSocket(0);
        url = "http://127.0.0.1:" + server.getLocalPort() + "/";
        transport = new Transport();
        serving = new Thread(this::serve);
        serving.setDaemon(true);
        serving.start();
    }

    @After
    public void tearDown() throws IOException {
        transport.close();
        server.close();
    }

    /** Accepts connections, each answering requests as the script says. */
    private void serve() {
        try {
            while (true) {
                Socket socket = server.accept();
                seen.add("accepted");
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
            while (true) {
                if (!readRequest(input)) {
                    seen.add("closed by the client");
                    return;
                }
                output.write(ANSWER);
                output.flush();
                Then then = script.poll(5, TimeUnit.SECONDS);
                if (then == Then.CLOSE) {
                    return;
                }
                if (then == Then.RESET) {
                    held.setSoLinger(true, 0);
                    return;
                }
            }
        } catch (IOException | InterruptedException gone) {
            // NOTE: the client went first.
        }
    }

    /** Reads one request head, false at the end of the stream. */
    private static boolean readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (true) {
            int read = input.read();
            if (read < 0) {
                return false;
            }
            head.write(read);
            byte[] bytes = head.toByteArray();
            int length = bytes.length;
            if (length >= 4
                    && bytes[length - 4] == '\r'
                    && bytes[length - 3] == '\n'
                    && bytes[length - 2] == '\r'
                    && bytes[length - 1] == '\n') {
                return true;
            }
        }
    }

    /** Sends the request and reads its answer whole, settling it. */
    private void exchange() throws IOException {
        transport.begin(url);
        transport.write(url, REQUEST);
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        while (answer.size() < ANSWER.length) {
            byte[] chunk = transport.read(url);
            if (chunk.length == 0) {
                fail("the answer ended early");
            }
            answer.write(chunk);
        }
        assertArrayEquals(ANSWER, answer.toByteArray());
        transport.settle(url);
    }

    private void accepted(int count) throws InterruptedException {
        for (int index = 0; index < count; index++) {
            assertEquals("accepted", seen.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aServerThatAnswersOnceAndClosesIsReconnected() throws Exception {
        script.add(Then.CLOSE);
        exchange();
        accepted(1);

        assertEquals(0, transport.read(url).length);
        assertEquals(1, transport.losses());

        script.add(Then.STAY);
        assertFalse("a dropped connection is not reused", transport.begin(url));
        exchange();
        accepted(1);
    }

    @Test
    public void aConnectionResetIsDroppedAndCounted() throws Exception {
        script.add(Then.RESET);
        exchange();
        accepted(1);

        // NOTE: the reset lands on the write or on the read after it,
        // whichever meets it first; the connection goes either way.
        Thread.sleep(200);
        try {
            transport.write(url, REQUEST);
            assertEquals(0, transport.read(url).length);
        } catch (IOException reset) {
            // NOTE: the expected end.
        }
        assertEquals(1, transport.losses());

        script.add(Then.STAY);
        exchange();
        accepted(1);
    }

    @Test
    public void aSettledConnectionIsReusedAnUnsettledOneDropped() throws Exception {
        script.add(Then.STAY);
        exchange();
        accepted(1);
        assertTrue("its answer was read whole", transport.begin(url));

        script.add(Then.STAY);
        transport.write(url, REQUEST);
        assertFalse(transport.begin(url));
        assertEquals("closed by the client", seen.poll(5, TimeUnit.SECONDS));
        script.add(Then.STAY);
        exchange();
        accepted(1);
        assertEquals("a drop asked for is no loss", 0, transport.losses());
    }

    @Test
    public void resetDropsTheConnection() throws Exception {
        script.add(Then.STAY);
        exchange();
        accepted(1);

        transport.reset(url);
        assertEquals("closed by the client", seen.poll(5, TimeUnit.SECONDS));
        script.add(Then.STAY);
        exchange();
        accepted(1);
    }

    /**
     * A credential never goes out on a plain socket, whatever asked for it.
     *
     * <p>The bytes are refused before they leave; a request carrying none goes
     * out.
     */
    @Test
    public void aCredentialIsRefusedAPlainSocket() throws Exception {
        byte[] credentialed =
                "GET / HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic amFuZTpz\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
        try {
            transport.write(url, credentialed);
            fail("an Authorization header went out over plain http");
        } catch (PimalayaException refused) {
            assertTrue(refused.getMessage().startsWith("Refused to send credentials unencrypted"));
            assertFalse(refused.getMessage().contains("amFuZTpz"));
        }

        script.add(Then.STAY);
        exchange();
    }

    /** The rule reads each protocol's credential, and nothing else. */
    @Test
    public void cleartextCredentialsAreToldApart() {
        assertTrue(Transport.cleartextCredential("imap", bytes("A1 AUTHENTICATE PLAIN\r\n")));
        assertTrue(Transport.cleartextCredential("imap", bytes("a login jane secret\r\n")));
        assertFalse(Transport.cleartextCredential("imap", bytes("A0 CAPABILITY\r\n")));
        assertTrue(Transport.cleartextCredential("smtp", bytes("AUTH PLAIN AGphbmUAcw==\r\n")));
        assertFalse(Transport.cleartextCredential("smtp", bytes("EHLO localhost\r\n")));
        assertFalse(
                "a body chunk is no head",
                Transport.cleartextCredential("http", bytes("Authorization: in a body\r\n")));
        assertFalse(Transport.cleartextCredential("tcp", bytes("AUTH x\r\n")));
    }

    /** A host java.net.URI cannot parse is a typed failure, not a crash. */
    @Test
    public void anUnparsableHostIsATypedFailure() throws Exception {
        try {
            transport.write("https://bad_host.example.org/", REQUEST);
            fail("an unparsable host was connected to");
        } catch (PimalayaException refused) {
            assertEquals("Transport URL names no host it can reach", refused.getMessage());
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
