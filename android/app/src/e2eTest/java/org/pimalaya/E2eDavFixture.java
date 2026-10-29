package org.pimalaya;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A CardDAV or CalDAV home prepared over WebDAV itself.
 *
 * <p>A scratch book is made by extended MKCOL (RFC 5689), a calendar by
 * MKCALENDAR (RFC 4791), named by the scratch name; an item is a PUT resource.
 * Only collections carrying the prefix are ever written or deleted.
 */
final class E2eDavFixture implements E2eItemFixture {
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern RESPONSE =
            Pattern.compile("<(?:\\w+:)?response\\b.*?</(?:\\w+:)?response>", Pattern.DOTALL);

    private static final Pattern HREF =
            Pattern.compile("<(?:\\w+:)?href>([^<]*)</(?:\\w+:)?href>");

    private static final Pattern NAME =
            Pattern.compile("<(?:\\w+:)?displayname>([^<]*)</(?:\\w+:)?displayname>");

    /** The collections' home, ending with a slash. */
    private final String home;

    private final boolean calendar;

    private final String authorization;

    E2eDavFixture(String home, boolean calendar, String user, String password) {
        this.home = home;
        this.calendar = calendar;
        this.authorization =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void createCollection(String name) throws Exception {
        createCollection(name, List.of());
    }

    @Override
    public void createCollection(String name, List<String> components) throws Exception {
        String props = "<D:set><D:prop><D:displayname>" + name + "</D:displayname>";
        if (calendar && !components.isEmpty()) {
            props += "<C:supported-calendar-component-set>";
            for (String component : components) {
                props += "<C:comp name=\"" + component + "\"/>";
            }
            props += "</C:supported-calendar-component-set>";
        }
        if (calendar) {
            send(
                    "MKCALENDAR",
                    home + name + "/",
                    "<?xml version=\"1.0\"?><C:mkcalendar xmlns:D=\"DAV:\""
                            + " xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                            + props
                            + "</D:prop></D:set></C:mkcalendar>",
                    null);
        } else {
            send(
                    "MKCOL",
                    home + name + "/",
                    "<?xml version=\"1.0\"?><D:mkcol xmlns:D=\"DAV:\""
                            + " xmlns:A=\"urn:ietf:params:xml:ns:carddav\">"
                            + props
                            + "<D:resourcetype><D:collection/><A:addressbook/></D:resourcetype>"
                            + "</D:prop></D:set></D:mkcol>",
                    null);
        }
    }

    @Override
    public void deleteCollection(String name) throws Exception {
        if (!name.startsWith(E2eFixture.PREFIX)) {
            throw new IllegalArgumentException("not a scratch collection: " + name);
        }
        delete(home + name + "/");
    }

    @Override
    public void seed(String collection, String title) throws Exception {
        String uid = UUID.randomUUID() + "@pimalaya-e2e.invalid";
        String body;
        if (calendar) {
            ZonedDateTime start =
                    ZonedDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(10).withMinute(0)
                            .withSecond(0).withNano(0);
            body =
                    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//e2e//EN\r\n"
                            + "BEGIN:VEVENT\r\nUID:" + uid + "\r\n"
                            + "DTSTAMP:" + UTC.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n"
                            + "DTSTART:" + UTC.format(start) + "\r\n"
                            + "DTEND:" + UTC.format(start.plusHours(1)) + "\r\n"
                            + "SUMMARY:" + title + "\r\n"
                            + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        } else {
            body =
                    "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + uid + "\r\nFN:" + title + "\r\nN:;"
                            + title + ";;;\r\nEND:VCARD\r\n";
        }
        send(
                "PUT",
                home + collection + "/" + UUID.randomUUID() + (calendar ? ".ics" : ".vcf"),
                body,
                calendar ? "text/calendar; charset=utf-8" : "text/vcard; charset=utf-8");
    }

    @Override
    public List<String> titles(String collection, String text) throws Exception {
        List<String> titles = new ArrayList<>();
        String url = home + collection + "/";
        for (String[] member : members(url)) {
            if (member[0].endsWith("/")) {
                continue;
            }
            String title = titleOf(send("GET", absolute(member[0]), null, null));
            if (title.contains(text)) {
                titles.add(title);
            }
        }
        return titles;
    }

    @Override
    public java.util.Map<String, String> objects(String collection) throws Exception {
        if (!collection.startsWith(E2eFixture.PREFIX)) {
            throw new IllegalArgumentException("not a scratch collection: " + collection);
        }
        java.util.Map<String, String> objects = new java.util.LinkedHashMap<>();
        for (String[] member : members(home + collection + "/")) {
            if (!member[0].endsWith("/")) {
                objects.put(segmentOf(member[0]), send("GET", absolute(member[0]), null, null));
            }
        }
        return objects;
    }

    @Override
    public void sweep(String tag) throws Exception {
        for (String[] member : members(home)) {
            String segment = segmentOf(member[0]);
            if (segment.startsWith(E2eFixture.PREFIX) && segment.contains(tag)) {
                delete(absolute(member[0]));
            }
        }
    }

    @Override
    public void sweepStale(LocalDateTime before) throws Exception {
        for (String[] member : members(home)) {
            String segment = segmentOf(member[0]);
            if (segment.startsWith(E2eFixture.PREFIX) && E2eFixture.stale(segment, before)) {
                delete(absolute(member[0]));
            }
        }
    }

    /**
     * A collection's members, its own entry left out: href and display name.
     */
    private List<String[]> members(String url) throws Exception {
        String answer =
                send(
                        "PROPFIND",
                        url,
                        "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\">"
                                + "<D:prop><D:displayname/></D:prop></D:propfind>",
                        "1");
        String own = URI.create(url).getPath();
        List<String[]> members = new ArrayList<>();
        Matcher response = RESPONSE.matcher(answer);
        while (response.find()) {
            Matcher href = HREF.matcher(response.group());
            if (!href.find()) {
                continue;
            }
            String path = URI.create(href.group(1).trim()).getPath();
            if (path.equals(own)) {
                continue;
            }
            Matcher name = NAME.matcher(response.group());
            members.add(new String[] {path, name.find() ? name.group(1) : ""});
        }
        return members;
    }

    /** The FN or SUMMARY of a vCard or iCalendar object, lines unfolded. */
    private String titleOf(String object) {
        String key = calendar ? "SUMMARY" : "FN";
        for (String line : object.replaceAll("\r?\n[ \t]", "").split("\r?\n")) {
            if (line.startsWith(key + ":") || line.startsWith(key + ";")) {
                return line.substring(line.indexOf(':') + 1);
            }
        }
        return "";
    }

    private String absolute(String path) {
        return URI.create(home).resolve(path).toString();
    }

    private static String segmentOf(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private void delete(String url) throws Exception {
        try {
            send("DELETE", url, null, null);
        } catch (IOException gone) {
            // NOTE: already gone, deleted by the app or a sweep meanwhile.
        }
    }

    /**
     * One WebDAV request over HTTP/1.1 on a TLS socket of its own.
     *
     * <p>HttpURLConnection refuses the WebDAV methods. {@code type} for a
     * non-XML body. A failure names the method, the URL and the answer, never a
     * header.
     */
    private String send(String method, String url, String body, String type) throws Exception {
        URI uri = URI.create(url);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(uri.getRawPath()).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(uri.getHost()).append("\r\n");
        head.append("Authorization: ").append(authorization).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        if (method.equals("PROPFIND")) {
            head.append("Depth: ").append(type).append("\r\n");
        }
        if (body != null) {
            String content =
                    type == null || method.equals("PROPFIND")
                            ? "application/xml; charset=utf-8"
                            : type;
            head.append("Content-Type: ").append(content).append("\r\n");
        }
        head.append("\r\n");

        try (SSLSocket socket =
                (SSLSocket) SSLSocketFactory.getDefault().createSocket(uri.getHost(), 443)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(60_000);
            OutputStream output = socket.getOutputStream();
            output.write(head.toString().getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.flush();

            InputStream input = new BufferedInputStream(socket.getInputStream());
            String status = line(input);
            int code = Integer.parseInt(status.split(" ")[1]);
            boolean chunked = false;
            for (String header; !(header = line(input)).isEmpty(); ) {
                String lower = header.toLowerCase(Locale.ROOT);
                chunked |= lower.startsWith("transfer-encoding:") && lower.contains("chunked");
            }
            String answer = new String(chunked ? chunks(input) : all(input), StandardCharsets.UTF_8);
            if (code >= 400) {
                String flat = answer.replace('\n', ' ');
                throw new IOException(
                        method + " " + url + " answered " + code + ": "
                                + (flat.length() > 300 ? flat.substring(0, 300) + "..." : flat));
            }
            return answer;
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int read; (read = input.read()) >= 0 && read != '\n'; ) {
            if (read != '\r') {
                bytes.write(read);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] chunks(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int size; (size = Integer.parseInt(line(input).split(";")[0].trim(), 16)) > 0; ) {
            for (int left = size; left > 0; ) {
                byte[] buffer = new byte[left];
                int read = input.read(buffer, 0, left);
                if (read < 0) {
                    throw new IOException("the answer ended inside a chunk");
                }
                bytes.write(buffer, 0, read);
                left -= read;
            }
            line(input);
        }
        return bytes.toByteArray();
    }

    private static byte[] all(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
}
