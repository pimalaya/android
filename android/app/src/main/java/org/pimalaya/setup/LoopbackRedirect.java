package org.pimalaya.setup;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves a custom-client grant's loopback redirect (RFC 8252 section 7.3).
 *
 * <p>Accepts connections until one asks for the expected path with the grant's
 * state, answering others with a 400. Each read is bounded in time and size, so
 * a local app connecting first can neither hang the wait nor fill the memory.
 */
final class LoopbackRedirect {
    /** The longest request line read, the redirect's query included. */
    static final int MAX_REQUEST_LINE = 8192;

    private LoopbackRedirect() {}

    /**
     * Waits for the redirect and rebuilds its URL from {@code origin}.
     *
     * <p>Throws once the server socket's own timeout passes. {@code path} is
     * the redirect URI's ("/" when none), {@code state} the CSRF state it must
     * carry.
     */
    static String await(
            ServerSocket server,
            String origin,
            String path,
            String state,
            int readTimeoutMs,
            String done)
            throws IOException {
        while (true) {
            // NOTE: accept throws once the server socket's own timeout
            // passes or it closes, which ends the wait; a slow or dropped
            // connection ends only itself.
            Socket socket = server.accept();
            try (Socket client = socket) {
                client.setSoTimeout(readTimeoutMs);
                String target = target(requestLine(client.getInputStream()));
                if (target != null && matches(target, path, state)) {
                    answer(client.getOutputStream(), "200 OK", done);
                    return origin + target;
                }
                answer(client.getOutputStream(), "400 Bad Request", "");
            } catch (IOException dropped) {
                // NOTE: the next connection may be the browser's.
            }
        }
    }

    /** The request's first line, at most {@link #MAX_REQUEST_LINE} bytes. */
    private static String requestLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < MAX_REQUEST_LINE) {
            int b = in.read();
            if (b == -1 || b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    /** "GET /?code=..&state=.. HTTP/1.1" gives "/?code=..&state=..". */
    private static String target(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[0].equals("GET") || !parts[1].startsWith("/")) {
            return null;
        }
        return parts[1];
    }

    private static boolean matches(String target, String path, String state) {
        Uri uri = Uri.parse("http://127.0.0.1" + target);
        String got = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        return got.equals(path) && state.equals(uri.getQueryParameter("state"));
    }

    private static void answer(OutputStream out, String status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head =
                "HTTP/1.1 "
                        + status
                        + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + bytes.length
                        + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
