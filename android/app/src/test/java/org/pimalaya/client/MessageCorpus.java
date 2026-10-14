package org.pimalaya.client;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Crafted RFC 5322 messages the mail read pins run through the real bridge.
 *
 * <p>0 plain, 1 alternative, 2 attachments (base64, RFC 2231, unnamed), 3
 * inline image, 4 encoded headers, 5 invitation, 6 forwarded, 7 headerless, 8
 * broken multipart (bad date, no From), 9 empty, 10 binary noise.
 */
public final class MessageCorpus {
    private MessageCorpus() {}

    private static String lines(String... lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    /** The messages, as bytes, the last ones broken. */
    public static final List<byte[]> MESSAGES =
            List.of(
                    lines(
                                    "From: Alice Martin <alice@example.org>",
                                    "To: bob@example.org",
                                    "Subject: Lunch",
                                    "Date: Tue, 06 Jan 2026 09:30:00 +0100",
                                    "Message-ID: <plain@example.org>",
                                    "Content-Type: text/plain; charset=utf-8",
                                    "",
                                    "See you at noon.")
                            .getBytes(StandardCharsets.UTF_8),
                    lines(
                                    "From: \"Doe, Jane\" <jane@example.org>",
                                    "To: Bob <bob@example.org>, carol@example.org",
                                    "Cc: Dan <dan@example.org>",
                                    "Subject: Agenda",
                                    "Date: Wed, 07 Jan 2026 18:00:00 -0500",
                                    "MIME-Version: 1.0",
                                    "Content-Type: multipart/alternative; boundary=\"alt\"",
                                    "",
                                    "--alt",
                                    "Content-Type: text/plain; charset=utf-8",
                                    "",
                                    "Plain agenda",
                                    "--alt",
                                    "Content-Type: text/html; charset=utf-8",
                                    "",
                                    "<p>HTML <b>agenda</b></p>",
                                    "--alt--")
                            .getBytes(StandardCharsets.UTF_8),
                    lines(
                                    "From: billing@example.org",
                                    "To: bob@example.org",
                                    "Subject: Invoice",
                                    "Date: Thu, 08 Jan 2026 10:00:00 +0000",
                                    "Content-Type: multipart/mixed; boundary=\"mix\"",
                                    "",
                                    "--mix",
                                    "Content-Type: text/plain",
                                    "",
                                    "Your invoice.",
                                    "--mix",
                                    "Content-Type: application/PDF; name=\"invoice.pdf\"",
                                    "Content-Disposition: attachment; filename=\"invoice.pdf\"",
                                    "Content-Transfer-Encoding: base64",
                                    "",
                                    "JVBERi0xLjQKJcOkw7zDtsOfCg==",
                                    "--mix",
                                    "Content-Type: text/plain; charset=utf-8",
                                    "Content-Disposition: attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.txt",
                                    "",
                                    "notes",
                                    "--mix",
                                    "Content-Type: application/octet-stream",
                                    "Content-Disposition: attachment",
                                    "",
                                    "raw",
                                    "--mix--")
                            .getBytes(StandardCharsets.UTF_8),
                    lines(
                                    "From: news@example.org",
                                    "To: bob@example.org",
                                    "Subject: Newsletter",
                                    "Date: Fri, 09 Jan 2026 07:00:00 +0000",
                                    "Content-Type: multipart/related; boundary=\"rel\"",
                                    "",
                                    "--rel",
                                    "Content-Type: text/html",
                                    "",
                                    "<img src=\"cid:logo@x\">",
                                    "--rel",
                                    "Content-Type: image/png",
                                    "Content-ID: <logo@x>",
                                    "Content-Disposition: inline; filename=\"logo.png\"",
                                    "Content-Transfer-Encoding: base64",
                                    "",
                                    "iVBORw0KGgo=",
                                    "--rel--")
                            .getBytes(StandardCharsets.UTF_8),
                    (lines(
                                            "From: =?ISO-8859-1?Q?Ren=E9_Dupr=E9?= <rene@example.fr>",
                                            "To: =?UTF-8?B?w4lsb2RpZQ==?= <elodie@example.fr>, x@example.fr",
                                            "Cc: =?UTF-8?Q?Z=C3=BCrich?= <zh@example.ch>",
                                            "Subject: =?UTF-8?B?Q2Fmw6kgZXQgY3LDqG1l?=",
                                            "Date: Sat, 10 Jan 2026 12:00:00 +0200",
                                            "Content-Type: text/plain; charset=iso-8859-1",
                                            "Content-Transfer-Encoding: quoted-printable",
                                            "",
                                            "Caf=E9 cr=E8me")
                                    .getBytes(StandardCharsets.UTF_8)),
                    lines(
                                    "From: Alice <alice@example.org>",
                                    "To: bob@example.org",
                                    "Message-ID: <inv@example.org>",
                                    "Subject: Review",
                                    "Date: Sun, 11 Jan 2026 08:00:00 +0000",
                                    "Content-Type: multipart/mixed; boundary=\"sep\"",
                                    "",
                                    "--sep",
                                    "Content-Type: multipart/alternative; boundary=\"alt\"",
                                    "",
                                    "--alt",
                                    "Content-Type: text/plain",
                                    "",
                                    "Join us",
                                    "--alt",
                                    "Content-Type: text/calendar; method=REQUEST; charset=utf-8",
                                    "",
                                    "BEGIN:VCALENDAR",
                                    "METHOD:REQUEST",
                                    "BEGIN:VEVENT",
                                    "UID:ev-1",
                                    "DTSTART:20260112T090000Z",
                                    "SUMMARY:Review",
                                    "END:VEVENT",
                                    "END:VCALENDAR",
                                    "--alt--",
                                    "--sep",
                                    "Content-Type: application/ics; name=\"invite.ics\"",
                                    "Content-Disposition: attachment; filename=\"invite.ics\"",
                                    "",
                                    "BEGIN:VCALENDAR",
                                    "END:VCALENDAR",
                                    "--sep--")
                            .getBytes(StandardCharsets.UTF_8),
                    lines(
                                    "From: bob@example.org",
                                    "To: carol@example.org",
                                    "Subject: Fwd: Lunch",
                                    "Date: Mon, 12 Jan 2026 10:00:00 +0000",
                                    "Content-Type: multipart/mixed; boundary=\"fwd\"",
                                    "",
                                    "--fwd",
                                    "Content-Type: text/plain",
                                    "",
                                    "Forwarding.",
                                    "--fwd",
                                    "Content-Type: message/rfc822",
                                    "Content-Disposition: attachment; filename=\"lunch.eml\"",
                                    "",
                                    "From: Alice <alice@example.org>",
                                    "Subject: Lunch",
                                    "",
                                    "See you at noon.",
                                    "--fwd--")
                            .getBytes(StandardCharsets.UTF_8),
                    lines("", "just a body").getBytes(StandardCharsets.UTF_8),
                    lines(
                                    "Subject: Broken",
                                    "Date: yesterday-ish",
                                    "Content-Type: multipart/mixed; boundary=\"gone\"",
                                    "",
                                    "--gone",
                                    "Content-Type: text/plain",
                                    "",
                                    "cut off")
                            .getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1, 2, '\r', '\n', (byte) 0x80});
}
