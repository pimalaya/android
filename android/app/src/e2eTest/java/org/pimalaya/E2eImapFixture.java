package org.pimalaya;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A target's IMAP server prepared over a session of its own (RFC 9051, the
 * few commands the suite needs): mailboxes created and deleted, messages
 * appended, tagged ones expunged by UID alone (RFC 4315), never by a
 * mailbox-wide EXPUNGE that would take another client's `\Deleted` mail.
 */
final class E2eImapFixture implements E2eFixture {
    private static final Pattern LITERAL = Pattern.compile("\\{(\\d+)\\+?}$");

    private static final DateTimeFormatter SEARCH_DATE =
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US);

    private final SSLSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private int next;
    private boolean uidplus;

    /**
     * Signs in as {@code user}: with LOGIN when {@code password} is given,
     * with SASL XOAUTH2 when {@code token} is.
     */
    E2eImapFixture(String host, String user, String password, String token) throws IOException {
        socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 993);
        socket.setSoTimeout(60_000);
        input = new BufferedInputStream(socket.getInputStream());
        output = socket.getOutputStream();
        line();
        if (password != null) {
            run("LOGIN " + quote(user) + " " + quote(password), true);
        } else {
            String initial =
                    Base64.getEncoder()
                            .encodeToString(
                                    ("user=" + user + "\1auth=Bearer " + token + "\1\1")
                                            .getBytes(StandardCharsets.UTF_8));
            run("AUTHENTICATE XOAUTH2 " + initial, true);
        }
        for (String line : run("CAPABILITY", false)) {
            if (line.startsWith("* CAPABILITY") && line.toUpperCase(Locale.ROOT).contains(" UIDPLUS")) {
                uidplus = true;
            }
        }
    }

    @Override
    public void createMailbox(String name) throws IOException {
        run("CREATE " + quote(name), false);
    }

    @Override
    public void deleteMailbox(String name) throws IOException {
        for (String found : mailboxes()) {
            if (found.equals(name) || found.endsWith("/" + name) || found.endsWith("." + name)) {
                run("UNSELECT", false, true);
                run("DELETE " + quote(found), false, true);
            }
        }
    }

    @Override
    public void seed(String mailbox, E2eMessage message) throws IOException {
        byte[] bytes = message.bytes();
        List<String> flags = new ArrayList<>(message.keywords);
        if (message.seen) {
            flags.add("\\Seen");
        }
        String tag = "f" + (++next);
        write(
                tag + " APPEND " + quote(mailbox) + " (" + String.join(" ", flags) + ") {"
                        + bytes.length + "}\r\n");
        String ready = line();
        if (!ready.startsWith("+")) {
            throw new IOException("APPEND refused: " + ready);
        }
        output.write(bytes);
        write("\r\n");
        until(tag, "APPEND", false);
    }

    @Override
    public List<String> keywords(String mailbox, String subject) throws IOException {
        run("EXAMINE " + quote(mailbox), false);
        List<String> keywords = new ArrayList<>();
        for (String uid : search("SUBJECT " + quote(subject))) {
            for (String line : run("UID FETCH " + uid + " (FLAGS)", false)) {
                Matcher flags = Pattern.compile("FLAGS \\(([^)]*)\\)").matcher(line);
                if (flags.find() && !flags.group(1).isBlank()) {
                    for (String flag : flags.group(1).split(" ")) {
                        keywords.add(flag);
                    }
                }
            }
        }
        return keywords;
    }

    @Override
    public List<String> where(String subject) throws IOException {
        List<String> found = new ArrayList<>();
        for (String mailbox : mailboxes()) {
            if (!run("EXAMINE " + quote(mailbox), false, true).isEmpty()
                    && !search("SUBJECT " + quote(subject)).isEmpty()) {
                found.add(mailbox);
            }
        }
        run("UNSELECT", false, true);
        return found;
    }

    @Override
    public void sweep(String tag) throws IOException {
        expunge("SUBJECT " + quote(tag));
    }

    @Override
    public void sweepStale(LocalDateTime before) throws IOException {
        for (String found : mailboxes()) {
            String leaf = found.substring(Math.max(found.lastIndexOf('/'), found.lastIndexOf('.')) + 1);
            if (leaf.startsWith(PREFIX) && E2eFixture.stale(leaf, before)) {
                run("UNSELECT", false, true);
                run("DELETE " + quote(found), false, true);
            }
        }
        expunge(
                "SUBJECT " + quote(PREFIX) + " SENTBEFORE "
                        + SEARCH_DATE.format(before.toLocalDate()));
    }

    @Override
    public void close() {
        try {
            run("LOGOUT", false, true);
        } catch (IOException ignored) {
            // NOTE: best effort, the socket closes either way.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // NOTE: nothing to do about a socket that will not close.
        }
    }

    /** Every message matching {@code criteria}, in every mailbox, expunged by UID. */
    private void expunge(String criteria) throws IOException {
        if (!uidplus) {
            return;
        }
        for (String mailbox : mailboxes()) {
            if (!run("SELECT " + quote(mailbox), false, true).isEmpty()) {
                List<String> uids = search(criteria);
                if (!uids.isEmpty()) {
                    String set = String.join(",", uids);
                    run("UID STORE " + set + " +FLAGS.SILENT (\\Deleted)", false);
                    run("UID EXPUNGE " + set, false);
                }
            }
        }
        run("UNSELECT", false, true);
    }

    private List<String> search(String criteria) throws IOException {
        List<String> uids = new ArrayList<>();
        for (String line : run("UID SEARCH " + criteria, false)) {
            if (line.startsWith("* SEARCH")) {
                for (String uid : line.substring("* SEARCH".length()).trim().split(" ")) {
                    if (!uid.isEmpty()) {
                        uids.add(uid);
                    }
                }
            }
        }
        return uids;
    }

    /** The selectable mailboxes, by name. */
    private List<String> mailboxes() throws IOException {
        Pattern list = Pattern.compile("^\\* LIST \\(([^)]*)\\) (?:\"[^\"]*\"|NIL) (.+)$");
        List<String> names = new ArrayList<>();
        for (String line : run("LIST \"\" \"*\"", false)) {
            Matcher match = list.matcher(line);
            if (!match.find() || match.group(1).toLowerCase(Locale.ROOT).contains("\\noselect")) {
                continue;
            }
            String name = match.group(2);
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            names.add(name);
        }
        return names;
    }

    private List<String> run(String command, boolean secret) throws IOException {
        return run(command, secret, false);
    }

    /**
     * Runs one command, answering its untagged lines; a refusal fails unless
     * {@code quiet}, which answers nothing instead. A {@code secret} command
     * is never named in a failure.
     */
    private List<String> run(String command, boolean secret, boolean quiet) throws IOException {
        String tag = "f" + (++next);
        write(tag + " " + command + "\r\n");
        String verb = secret ? command.substring(0, command.indexOf(' ')) : command;
        try {
            return until(tag, verb, secret);
        } catch (IOException refused) {
            if (quiet) {
                return List.of();
            }
            throw refused;
        }
    }

    private List<String> until(String tag, String verb, boolean secret) throws IOException {
        List<String> untagged = new ArrayList<>();
        while (true) {
            String line = line();
            if (line.startsWith("+")) {
                // NOTE: a SASL failure asks for an empty answer before its NO.
                write("\r\n");
                continue;
            }
            if (!line.startsWith(tag + " ")) {
                untagged.add(line);
                continue;
            }
            if (line.startsWith(tag + " OK")) {
                return untagged;
            }
            throw new IOException(verb + " refused: " + (secret ? "" : line));
        }
    }

    /** One response line, a literal it announces read into it. */
    private String line() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int read;
            while ((read = input.read()) != -1 && read != '\n') {
                if (read != '\r') {
                    bytes.write(read);
                }
            }
            if (read == -1 && bytes.size() == 0) {
                throw new IOException("the IMAP server closed the connection");
            }
            String part = bytes.toString(StandardCharsets.UTF_8.name());
            line.append(part);
            Matcher literal = LITERAL.matcher(part);
            if (!literal.find()) {
                return line.toString();
            }
            byte[] content = new byte[Integer.parseInt(literal.group(1))];
            int at = 0;
            while (at < content.length) {
                int got = input.read(content, at, content.length - at);
                if (got < 0) {
                    throw new IOException("the IMAP server closed the connection");
                }
                at += got;
            }
            line.setLength(line.length() - literal.group().length());
            line.append('"').append(new String(content, StandardCharsets.UTF_8)).append('"');
        }
    }

    private void write(String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
