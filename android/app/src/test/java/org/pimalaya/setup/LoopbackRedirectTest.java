package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The custom-client loopback listener against a local app connecting first.
 *
 * <p>Silent or sending a stray request, it neither hangs the wait nor gets the
 * grant's redirect taken for its own.
 */
@RunWith(RobolectricTestRunner.class)
public class LoopbackRedirectTest {
    private static final String STATE = "st4te";

    private ServerSocket server;
    private ExecutorService waiter;

    @Before
    public void setUp() throws Exception {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(20_000);
        waiter = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        waiter.shutdownNow();
    }

    private Future<String> await() {
        return waiter.submit(
                () ->
                        LoopbackRedirect.await(
                                server, "http://127.0.0.1:1234", "/cb", STATE, 300, "done"));
    }

    /** Sends {@code request} and reads the whole answer. */
    private String send(String request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1; ) {
                answer.write(buffer, 0, n);
            }
            return answer.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test
    public void strayConnectionsAreTurnedAwayAndTheRedirectServed() throws Exception {
        Future<String> redirect = await();

        try (Socket silent =
                new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort())) {
            silent.setSoTimeout(5_000);
            assertEquals(-1, silent.getInputStream().read());
        } catch (SocketTimeoutException hung) {
            throw new AssertionError("a silent connection held the listener", hung);
        }

        assertTrue(send("GET /cb?code=c&state=forged HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(send("GET /other?code=c&state=" + STATE + " HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
        // NOTE: a line past the bound is cut and refused; the listener may
        // close with the rest unread, which resets the connection.
        try {
            String answer = send("x".repeat(LoopbackRedirect.MAX_REQUEST_LINE * 4));
            assertTrue(answer.isEmpty() || answer.startsWith("HTTP/1.1 400"));
        } catch (java.io.IOException reset) {
            // NOTE: a reset is a refusal too.
        }

        String answer = send("GET /cb?code=c0de&state=" + STATE + " HTTP/1.1\r\nHost: x\r\n\r\n");
        assertTrue(answer.startsWith("HTTP/1.1 200"));
        assertTrue(answer.endsWith("done"));
        assertEquals(
                "http://127.0.0.1:1234/cb?code=c0de&state=" + STATE,
                redirect.get(10, TimeUnit.SECONDS));
    }
}
