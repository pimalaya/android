package org.pimalaya.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * URL-keyed socket pool the bridge calls by name (JNI), one per origin.
 *
 * <p>TLS schemes get a platform-validated socket, plain ones one that
 * {@link #starttls} upgrades; a failed, ended or unsettled one is dropped.
 * Not thread-safe: one caller at a time, a fan-out gives each worker its own.
 */
public final class Transport implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final class Connection {
        final Socket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;

        /** Whether its last HTTP answer was read whole; false until settled. */
        boolean settled = true;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }
    }

    private final Map<String, Connection> connections = new HashMap<>();
    private final byte[] buffer = new byte[16 * 1024];
    private int losses;

    /**
     * Reads the next chunk from the URL's stream; empty signals EOF.
     *
     * <p>A connection that failed, timed out or ended is dropped: a read
     * that timed out leaves its answer on the way, and the next request
     * must never read it as its own.
     */
    public byte[] read(String url) throws IOException {
        Connection connection = connect(url);
        int read;
        try {
            read = connection.input.read(buffer);
        } catch (IOException failure) {
            lose(url);
            throw failure;
        }

        if (read <= 0) {
            lose(url);
            return new byte[0];
        }

        byte[] chunk = new byte[read];
        System.arraycopy(buffer, 0, chunk, 0, read);
        return chunk;
    }

    /**
     * Writes and flushes every byte to the URL's stream, dropped on failure.
     *
     * <p>Refused when the bytes carry a credential over an unencrypted socket
     * ({@link #cleartextCredential}), the bridge's own rule where the socket
     * is known.
     */
    public void write(String url, byte[] bytes) throws IOException {
        Connection connection = connect(url);
        if (!(connection.socket instanceof SSLSocket)
                && cleartextCredential(schemeOf(uri(url)), bytes)) {
            throw new PimalayaException(
                    "Refused to send credentials unencrypted to " + originOf(uri(url)));
        }
        connection.settled = false;
        try {
            connection.output.write(bytes);
            connection.output.flush();
        } catch (IOException failure) {
            lose(url);
            throw failure;
        }
    }

    /**
     * Opens an HTTP request on the URL's origin: whether it reuses one.
     *
     * <p>An unsettled connection (a coroutine stopped half-way, an unframed
     * body, {@code Connection: close}) is dropped first. A reused one may have
     * been closed by the server while it idled.
     */
    public boolean begin(String url) {
        String origin = originOf(uri(url));
        Connection connection = connections.get(origin);
        if (connection != null && !connection.settled) {
            drop(origin);
            connection = null;
        }
        return connection != null;
    }

    /** Marks the URL's last answer read whole, so its connection is reused. */
    public void settle(String url) {
        Connection connection = connections.get(originOf(uri(url)));
        if (connection != null) {
            connection.settled = true;
        }
    }

    /** Drops the URL's connection, the next use opening another. */
    public void reset(String url) {
        drop(originOf(uri(url)));
    }

    /**
     * How many connections died under their caller since the transport began.
     *
     * <p>A failure, timeout or end of stream: a verb that failed while this
     * moved failed on its connection.
     */
    public int losses() {
        return losses;
    }

    private void lose(String url) {
        if (drop(originOf(uri(url)))) {
            losses++;
        }
    }

    private boolean drop(String origin) {
        Connection connection = connections.remove(origin);
        if (connection == null) {
            return false;
        }
        try {
            connection.socket.close();
        } catch (IOException ignored) {
            // NOTE: best-effort; the connection is forgotten either way.
        }
        return true;
    }

    /**
     * Upgrades the URL's plain socket to TLS in place (RFC 3207).
     *
     * <p>Refused when the server sent bytes past the agreeing reply: they came
     * in the clear, and reading them after the handshake is how a STARTTLS
     * injection smuggles a response across.
     */
    public void starttls(String url) throws IOException {
        URI uri = uri(url);
        String origin = originOf(uri);
        Connection connection = connections.get(origin);
        if (connection == null) {
            throw new PimalayaException("No connection to upgrade to " + origin);
        }
        if (connection.socket instanceof SSLSocket) {
            throw new PimalayaException("Connection to " + origin + " is already encrypted");
        }
        if (connection.input.available() > 0) {
            throw new PimalayaException(
                    "Server at " + origin + " sent data before the TLS upgrade");
        }

        Socket socket = tls(connection.socket, hostOf(uri), portOf(uri));

        connections.put(origin, new Connection(socket));
    }

    /** Closes every open socket; call once the native operation returns. */
    public void close() {
        for (Connection connection : connections.values()) {
            try {
                connection.socket.close();
            } catch (IOException ignored) {
                // NOTE: best-effort; the pool is dropped either way.
            }
        }
        connections.clear();
    }

    private Connection connect(String url) throws IOException {
        URI uri = uri(url);
        String scheme = schemeOf(uri);
        String host = hostOf(uri);
        int port = portOf(uri);
        String origin = originOf(uri);

        Connection connection = connections.get(origin);
        if (connection != null) {
            return connection;
        }

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        plain.setSoTimeout(READ_TIMEOUT_MS);

        Socket socket;
        switch (scheme) {
            case "https":
            case "imaps":
            case "smtps":
                // NOTE: the host-aware overload keeps the peer host, so
                // TLS gets SNI; tls() checks the certificate names it.
                socket = tls(plain, host, port);
                break;
            case "http":
            case "imap":
            case "smtp":
            case "tcp":
                socket = plain;
                break;
            default:
                plain.close();
                throw new PimalayaException("Unsupported transport scheme '" + scheme + "'");
        }

        connection = new Connection(socket);
        connections.put(origin, connection);
        return connection;
    }

    /** The pool key: scheme, host and port, any userinfo left out. */
    private static String originOf(URI uri) {
        return schemeOf(uri) + "://" + uri.getHost() + ":" + portOf(uri);
    }

    /**
     * The URL as a URI with a scheme and a host, or a typed failure.
     *
     * <p>A host {@link URI} cannot parse (an underscore in it) leaves it null.
     */
    private static URI uri(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new PimalayaException("Invalid transport URL: " + invalid.getMessage());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new PimalayaException("Transport URL names no host it can reach");
        }
        return uri;
    }

    private static String schemeOf(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether bytes about to go out on a plain connection carry a credential.
     *
     * <p>An HTTP {@code Authorization} header, an IMAP {@code AUTHENTICATE} or
     * {@code LOGIN}, an SMTP {@code AUTH}: only encrypted sockets carry those.
     */
    static boolean cleartextCredential(String scheme, byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\\r?\\n", -1);
        switch (scheme) {
            case "http":
                if (lines.length == 0 || !lines[0].matches("\\S+ \\S+ HTTP/1\\.[01]")) {
                    return false;
                }
                for (int at = 1; at < lines.length && !lines[at].isEmpty(); at++) {
                    if (lines[at].regionMatches(true, 0, "authorization:", 0, 14)) {
                        return true;
                    }
                }
                return false;
            case "imap":
                for (String line : lines) {
                    String[] words = line.trim().split(" +");
                    if (words.length > 1
                            && (words[1].equalsIgnoreCase("AUTHENTICATE")
                                    || words[1].equalsIgnoreCase("LOGIN"))) {
                        return true;
                    }
                }
                return false;
            case "smtp":
                for (String line : lines) {
                    if (line.regionMatches(true, 0, "AUTH ", 0, 5)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Wraps a socket in TLS to {@code host}, checking the certificate names it.
     *
     * <p>A factory {@link SSLSocket} validates the chain, not the hostname, so
     * any CA's certificate for another domain would pass; the check is the
     * platform's HTTPS rule.
     */
    private static Socket tls(Socket plain, String host, int port) throws IOException {
        SSLSocket socket =
                (SSLSocket)
                        ((SSLSocketFactory) SSLSocketFactory.getDefault())
                                .createSocket(plain, host, port, true);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession())) {
            socket.close();
            throw new SSLPeerUnverifiedException("Certificate does not match " + host);
        }
        return socket;
    }

    /** The host to connect to, bare. */
    private static String hostOf(URI uri) {
        String host = uri.getHost();
        // NOTE: URI keeps brackets around IPv6 literals; the socket API
        // wants the bare address.
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static int portOf(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : defaultPort(schemeOf(uri));
    }

    private static int defaultPort(String scheme) {
        switch (scheme) {
            case "https":
                return 443;
            case "http":
                return 80;
            case "imaps":
                return 993;
            case "imap":
                return 143;
            case "smtps":
                return 465;
            case "smtp":
                return 587;
            default:
                throw new PimalayaException("Transport URL '" + scheme + "' has no port");
        }
    }
}
