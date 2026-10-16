package org.pimalaya.mail;

import android.graphics.Typeface;
import android.text.Html;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Styled text as Markdown: the rich editor's, or a message's HTML.
 *
 * <p>Bold, italic, strikethrough, monospace, links, bulleted and numbered
 * lines and quotes become Markdown; anything else is dropped. The text's own
 * syntax is escaped, so a typed or quoted {@code 2*3*4} stays text.
 */
final class SpannedMarkdown {
    /** Characters Markdown would read as syntax anywhere in a line. */
    private static final String INLINE = "\\*_`[]~<";

    /** The link schemes a rich message sends; others keep their text. */
    private static final String[] SCHEMES = {"http://", "https://", "mailto:"};

    private SpannedMarkdown() {}

    /**
     * A message's HTML as Markdown, for a rich reply or forward to carry.
     *
     * <p>Read by the platform into spans, so its own HTML is never sent
     * again; its blank lines fold into one between blocks.
     */
    static String html(String html) {
        return of(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT), true);
    }

    /** The rich editor's text as Markdown, its blank lines as typed. */
    static String of(Spanned spanned) {
        return of(spanned, false);
    }

    private static String of(Spanned spanned, boolean fold) {
        String text = spanned.toString();
        List<Inline> inlines = inlines(spanned);

        StringBuilder out = new StringBuilder();
        String previous = null;
        String previousQuotes = "";
        int number = 0;
        boolean parted = false;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String quotes = quotes(spanned, start);
            String item = item(spanned, start);
            String line = line(text, start, end, inlines);
            start = end + 1;
            if (line.isBlank()) {
                if (!fold) {
                    out.append(quotes.stripTrailing()).append('\n');
                    previous = null;
                }
                parted = true;
                number = 0;
                continue;
            }

            number = "ordered".equals(item) ? number + 1 : 0;
            String block =
                    quotes
                            + switch (item) {
                                case "bullet" -> "- ";
                                case "ordered" -> number + ". ";
                                default -> "";
                            };
            String kind = quotes + "|" + item;
            // NOTE: one blank line where a list or a quote ends, which would
            // run on into the next line otherwise, and where folded HTML had
            // any between paragraphs.
            if (previous != null && (fold && parted || !previous.equals(kind))) {
                String shared = quotes.length() < previousQuotes.length() ? quotes : previousQuotes;
                out.append(shared.stripTrailing()).append('\n');
            }
            out.append(block).append(item.isEmpty() ? escapeStart(line) : line).append('\n');
            previous = kind;
            previousQuotes = quotes;
            parted = false;
        }
        return fold ? out.toString().strip() : out.toString().stripTrailing();
    }

    /** One {@code > } per quote the line sits in. */
    private static String quotes(Spanned spanned, int at) {
        StringBuilder quotes = new StringBuilder();
        for (QuoteSpan quote : spanned.getSpans(at, at, QuoteSpan.class)) {
            if (covers(spanned, quote, at)) {
                quotes.append("> ");
            }
        }
        return quotes.toString();
    }

    /** Whether the line is a list item: {@code bullet}, {@code ordered}, or empty. */
    private static String item(Spanned spanned, int at) {
        for (NumberSpan number : spanned.getSpans(at, at, NumberSpan.class)) {
            if (covers(spanned, number, at)) {
                return "ordered";
            }
        }
        for (BulletSpan bullet : spanned.getSpans(at, at, BulletSpan.class)) {
            if (covers(spanned, bullet, at)) {
                return "bullet";
            }
        }
        return "";
    }

    /** Whether a line's span holds its start, an empty last line's too. */
    private static boolean covers(Spanned spanned, Object span, int at) {
        int start = spanned.getSpanStart(span);
        int end = spanned.getSpanEnd(span);
        return start <= at && (at < end || at == end && at == spanned.length());
    }

    /** One line's text, its inline styles as markers, its syntax escaped. */
    private static String line(String text, int start, int end, List<Inline> inlines) {
        List<Inline> here = new ArrayList<>();
        for (Inline inline : inlines) {
            int from = Math.max(inline.start, start);
            int to = Math.min(inline.end, end);
            // NOTE: Markdown takes no space inside a marker.
            while (from < to && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }
            if (from < to) {
                here.add(new Inline(from, to, inline.open, inline.close));
            }
        }
        // NOTE: the longest opens first and closes last, so they nest.
        here.sort((a, b) -> a.start != b.start ? a.start - b.start : b.end - a.end);

        StringBuilder line = new StringBuilder();
        int code = 0;
        for (int at = start; at <= end; at++) {
            for (int index = here.size() - 1; index >= 0; index--) {
                Inline inline = here.get(index);
                if (inline.end == at) {
                    line.append(inline.close);
                    code -= "`".equals(inline.open) ? 1 : 0;
                }
            }
            if (at == end) {
                break;
            }
            for (Inline inline : here) {
                if (inline.start == at) {
                    line.append(inline.open);
                    code += "`".equals(inline.open) ? 1 : 0;
                }
            }
            char character = text.charAt(at);
            // NOTE: an image's placeholder, which nothing reads.
            if (character == '￼') {
                continue;
            }
            // NOTE: code shows its text as is, backslashes included.
            if (code == 0 && INLINE.indexOf(character) >= 0) {
                line.append('\\');
            }
            line.append(character);
        }
        return line.toString().stripTrailing();
    }

    /** Escapes what would start a block: a heading, a quote, a list. */
    private static String escapeStart(String line) {
        if (line.matches("^(#|>|[-+]\\s).*")) {
            return "\\" + line;
        }
        if (line.matches("^\\d+[.)]\\s.*")) {
            int dot = line.indexOf('.') >= 0 && line.indexOf('.') < line.indexOf(' ')
                    ? line.indexOf('.')
                    : line.indexOf(')');
            return line.substring(0, dot) + "\\" + line.substring(dot);
        }
        return line;
    }

    /**
     * The spans that become inline markers, each style's ranges merged.
     *
     * <p>Two bold spans touching are one bold run, else they would write
     * markers inside each other.
     */
    private static List<Inline> inlines(Spanned spanned) {
        Map<String, List<Inline>> byMarkers = new LinkedHashMap<>();
        for (Object span : spanned.getSpans(0, spanned.length(), Object.class)) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            if (span instanceof StyleSpan style) {
                if ((style.getStyle() & Typeface.BOLD) != 0) {
                    add(byMarkers, new Inline(start, end, "**", "**"));
                }
                if ((style.getStyle() & Typeface.ITALIC) != 0) {
                    add(byMarkers, new Inline(start, end, "*", "*"));
                }
            } else if (span instanceof StrikethroughSpan) {
                add(byMarkers, new Inline(start, end, "~~", "~~"));
            } else if (span instanceof CodeSpan
                    || span instanceof TypefaceSpan typeface
                            && "monospace".equals(typeface.getFamily())) {
                add(byMarkers, new Inline(start, end, "`", "`"));
            } else if (span instanceof URLSpan link && sent(link.getURL())) {
                add(byMarkers, new Inline(start, end, "[", "](" + link.getURL() + ")"));
            }
        }

        List<Inline> merged = new ArrayList<>();
        for (List<Inline> ranges : byMarkers.values()) {
            ranges.sort((a, b) -> a.start - b.start);
            Inline run = null;
            for (Inline range : ranges) {
                if (run != null && range.start <= run.end) {
                    run = new Inline(run.start, Math.max(run.end, range.end), run.open, run.close);
                    continue;
                }
                if (run != null) {
                    merged.add(run);
                }
                run = range;
            }
            if (run != null) {
                merged.add(run);
            }
        }
        return merged;
    }

    private static void add(Map<String, List<Inline>> byMarkers, Inline inline) {
        if (inline.start < inline.end) {
            byMarkers.computeIfAbsent(inline.open + inline.close, key -> new ArrayList<>()).add(inline);
        }
    }

    /** Whether a link's scheme is one a rich message sends. */
    private static boolean sent(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String scheme : SCHEMES) {
            if (lower.startsWith(scheme)) {
                return !url.contains(")") && !url.contains(" ");
            }
        }
        return false;
    }

    /** One inline style over a range, as the markers around it. */
    private record Inline(int start, int end, String open, String close) {}
}
