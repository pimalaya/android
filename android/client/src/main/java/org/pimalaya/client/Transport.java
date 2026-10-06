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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * URL-keyed pool of sockets for the Rust bridge. The native driver calls
 * {@link #read} / {@link #write} by name on every yield, passing the
 * endpoint URL it wants to talk to; the pool lazily opens one connection
 * per origin (scheme, host, port) and reuses it for as long as the
 * transport is held. A {@code tcp} (DNS resolver), {@code http} or
 * {@code smtp} URL gets a plain socket, an {@code https} one a TLS socket
 * validated by the platform trust store, and a plain one can be upgraded in
 * place ({@link #starttls}).
 *
 * <p>How long it is held is the whole of what it costs. A sync pass that
 * opened one per verb paid a connect, a TLS handshake and, over IMAP, an
 * authentication to carry each command; one held for the pass pays them
 * once. The sockets are the cheap half of that on IMAP, where the session
 * above them has to last too ({@link MailSession}); on the HTTP backends
 * they are the whole of it, a request carrying its own state.
 *
 * <p>Not thread-safe, and that is the whole of its contract: one
 * transport serves one caller at a time. It is held for as long as the
 * caller wants its sockets kept, which is a whole sync pass rather than
 * one call, and a fan-out gives each worker its own rather than sharing.
 */
public final class Transport implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final class Connection {
        final Socket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }
    }

    private final Map<String, Connection> connections = new HashMap<>();
    private final byte[] buffer = new byte[16 * 1024];

    /** Reads the next chunk from the URL's stream; empty signals EOF. */
    public byte[] read(String url) throws IOException {
        Connection connection = connect(url);
        int read = connection.input.read(buffer);

        if (read <= 0) {
            return new byte[0];
        }

        byte[] chunk = new byte[read];
        System.arraycopy(buffer, 0, chunk, 0, read);
        return chunk;
    }

    /** Writes and flushes every byte to the URL's stream. */
    public void write(String url, byte[] bytes) throws IOException {
        Connection connection = connect(url);
        connection.output.write(bytes);
        connection.output.flush();
    }

    /**
     * Upgrades the URL's plain socket to TLS in place, for a protocol that
     * negotiates it on the connection it opened (RFC 3207).
     *
     * <p>Refused when the server already sent something past the reply that
     * agreed to it: those bytes arrived in the clear, and reading them after
     * the handshake is how a STARTTLS injection smuggles a response across.
     */
    public void starttls(String url) throws IOException {
        URI uri = URI.create(url);
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

        Socket socket =
                ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(connection.socket, hostOf(uri), portOf(uri), true);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        ((SSLSocket) socket).startHandshake();

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
        URI uri = URI.create(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
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
                // TLS gets SNI and the platform validates the chain.
                socket =
                        ((SSLSocketFactory) SSLSocketFactory.getDefault())
                                .createSocket(plain, host, port, true);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                ((SSLSocket) socket).startHandshake();
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
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost() + ":" + portOf(uri);
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
        return uri.getPort() != -1
                ? uri.getPort()
                : defaultPort(uri.getScheme().toLowerCase(Locale.ROOT));
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
