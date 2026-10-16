package org.pimalaya.mail;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;
import org.pimalaya.R;
import org.pimalaya.client.MarkdownText;

/**
 * The composer's text: styled as it is read, Markdown as it is sent.
 *
 * <p>A rich message shows no syntax: bold is bold, a list has its bullets.
 * Markdown is read in ({@link #open}) and written out ({@link #markdown}) at
 * the edges only, so the caret never meets a marker. A plain message is the
 * same field, its text the Markdown as typed.
 */
public final class MarkdownField extends EditText {
    /** The inline styles the formatting bar sets. */
    private static final String[] INLINE = {"strong", "emphasis", "strike", "code", "link"};

    /** Whether the text is rich: styled, and sent with its HTML. */
    private boolean rich;

    /** Told when the caret or the styles move, so the bar follows. */
    private Runnable onState;

    /** Set while the field edits its own text, which it does not watch. */
    private boolean editing;

    /** The last change, for the list rules that answer a typed key. */
    private int changeStart;
    private int changeCount;
    private int changeBefore;

    private final int secondary;
    private final int faint;
    private final int margin;

    /** Inflated from a layout. */
    public MarkdownField(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray colors =
                context.obtainStyledAttributes(new int[] {android.R.attr.textColorSecondary});
        secondary = colors.getColor(0, 0);
        colors.recycle();
        faint = context.getColorStateList(R.color.faint).getDefaultColor();
        margin = Math.round(28 * getResources().getDisplayMetrics().density);

        addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        changeStart = start;
                        changeBefore = before;
                        changeCount = count;
                    }

                    @Override
                    public void afterTextChanged(Editable text) {
                        if (editing || !rich) {
                            return;
                        }
                        editing = true;
                        try {
                            if (!leaveEmptyItem(text)) {
                                startBlock(text);
                            }
                            lines(text);
                        } finally {
                            editing = false;
                        }
                        changed();
                    }
                });
    }

    /** Opens on Markdown, rich (styled) or plain (as written). */
    public void open(String markdown, boolean rich) {
        this.rich = rich;
        load(markdown);
    }

    public boolean isRich() {
        return rich;
    }

    /** The message as Markdown: the styles written out, or the plain text. */
    public String markdown() {
        return rich ? SpannedMarkdown.of(getText()) : getText().toString();
    }

    /** Runs whenever the caret or the styles move. */
    public void setOnStateChanged(Runnable onState) {
        this.onState = onState;
    }

    @Override
    protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        changed();
    }

    /** Whether the caret, or the whole selection, is in a {@code kind}. */
    public boolean active(String kind) {
        Editable text = getText();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (block(kind)) {
            return lineSpan(text, kind, lineStart(text, start)) != null;
        }
        return start == end ? at(text, kind, start) != null : covered(text, kind, start, end);
    }

    /**
     * Bold, italic, strikethrough or code: off where the caret or the whole
     * selection is in it, else on, for what is typed next at a caret.
     */
    public void toggle(String kind) {
        Editable text = getText();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start == end) {
            Object inside = at(text, kind, start);
            if (inside != null) {
                split(text, inside, start);
            } else {
                text.setSpan(create(kind, null), start, start, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
        } else if (covered(text, kind, start, end)) {
            strip(text, kind, start, end);
        } else {
            text.setSpan(create(kind, null), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        changed();
    }

    /** The link the caret or the selection is in, or null outside one. */
    public Link link() {
        Editable text = getText();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        for (URLSpan span : text.getSpans(start, end, URLSpan.class)) {
            int from = text.getSpanStart(span);
            int to = text.getSpanEnd(span);
            if (from <= start && end <= to) {
                return new Link(from, to, text.subSequence(from, to).toString(), span.getURL());
            }
        }
        return null;
    }

    /** The selected text, as a new link's text. */
    public String selected() {
        return getText().subSequence(getSelectionStart(), getSelectionEnd()).toString();
    }

    /**
     * Writes a link over {@code replaced} (a link the caret was in) or the
     * selection; an empty address leaves its text alone.
     */
    public void writeLink(Link replaced, String label, String url) {
        Editable text = getText();
        int start = replaced != null ? replaced.start() : getSelectionStart();
        int end = replaced != null ? replaced.end() : getSelectionEnd();
        for (URLSpan span : text.getSpans(start, end, URLSpan.class)) {
            text.removeSpan(span);
        }
        text.replace(start, end, label);
        if (!url.isEmpty()) {
            text.setSpan(
                    new URLSpan(url), start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        setSelection(start + label.length());
        changed();
    }

    /**
     * A bulleted list, a numbered one or a quote over the selected lines: off
     * when every line already is one, else on.
     */
    public void toggleLines(String kind) {
        Editable text = getText();
        List<Integer> starts = new ArrayList<>();
        int line = lineStart(text, getSelectionStart());
        int last = lineStart(text, getSelectionEnd());
        while (true) {
            starts.add(line);
            if (line >= last) {
                break;
            }
            line = lineEnd(text, line) + 1;
        }

        boolean every = true;
        for (int start : starts) {
            every &= lineSpan(text, kind, start) != null;
        }
        editing = true;
        try {
            for (int start : starts) {
                if (every) {
                    text.removeSpan(lineSpan(text, kind, start));
                    continue;
                }
                if (!"quote".equals(kind)) {
                    // NOTE: a line is one list at a time.
                    for (String list : new String[] {"bullet", "ordered"}) {
                        Object span = lineSpan(text, list, start);
                        if (span != null) {
                            text.removeSpan(span);
                        }
                    }
                }
                setLine(text, kind, start);
            }
        } finally {
            editing = false;
        }
        changed();
    }

    /** Takes off every inline style over the selection, or at the caret. */
    public void clearInline() {
        Editable text = getText();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        for (String kind : INLINE) {
            if (start == end) {
                Object inside = at(text, kind, start);
                if (inside != null) {
                    text.removeSpan(inside);
                }
            } else {
                strip(text, kind, start, end);
            }
        }
        changed();
    }

    /** Fills the field: Markdown read into styled text, or plain as is. */
    private void load(String markdown) {
        editing = true;
        try {
            if (rich) {
                // NOTE: Markdown drops the blank lines a reply opens with,
                // above its quote, where the reply is written.
                String body = markdown.replaceFirst("^\n+", "");
                SpannableStringBuilder text = styled(body);
                text.insert(0, markdown.substring(0, markdown.length() - body.length()));
                setText(text);
            } else {
                setText(markdown);
            }
        } finally {
            editing = false;
        }
        changed();
    }

    /** Markdown as the styled text the rich field shows. */
    private SpannableStringBuilder styled(String markdown) {
        MarkdownText view = MarkdownText.of(markdown);
        SpannableStringBuilder text = new SpannableStringBuilder(view.text());
        for (MarkdownText.Style style : view.styles()) {
            int start = Math.min(style.start(), text.length());
            int end = Math.min(style.end(), text.length());
            switch (style.kind()) {
                case "bullet", "ordered" -> setLine(text, style.kind(), lineStart(text, start));
                case "quote" -> {
                    int line = lineStart(text, start);
                    while (true) {
                        setLine(text, "quote", line);
                        int next = lineEnd(text, line);
                        if (next >= end) {
                            break;
                        }
                        line = next + 1;
                    }
                }
                case "link" ->
                        text.setSpan(
                                new URLSpan(style.url()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                default ->
                        text.setSpan(
                                create(style.kind(), null),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
        }
        return text;
    }

    /**
     * Enter on an empty item or quoted line leaves the list or the quote, as
     * every editor does; answers whether it did.
     */
    private boolean leaveEmptyItem(Editable text) {
        if (changeBefore != 0
                || changeCount != 1
                || changeStart >= text.length()
                || text.charAt(changeStart) != '\n'
                || lineStart(text, changeStart) != changeStart) {
            return false;
        }
        boolean held = false;
        for (String kind : new String[] {"bullet", "ordered", "quote"}) {
            Object span;
            while ((span = lineSpan(text, kind, changeStart)) != null) {
                text.removeSpan(span);
                held = true;
            }
        }
        if (held) {
            text.delete(changeStart, changeStart + 1);
        }
        return held;
    }

    /** {@code - }, {@code 1. } or {@code > } typed at a line's start. */
    private void startBlock(Editable text) {
        if (changeBefore != 0
                || changeCount != 1
                || changeStart >= text.length()
                || text.charAt(changeStart) != ' ') {
            return;
        }
        int line = lineStart(text, changeStart);
        String typed = text.subSequence(line, changeStart + 1).toString();
        String kind =
                typed.matches("[-*+] ")
                        ? "bullet"
                        : typed.matches("\\d+[.)] ") ? "ordered" : typed.equals("> ") ? "quote" : null;
        if (kind == null || !"quote".equals(kind) && lineSpan(text, kind, line) != null) {
            return;
        }
        text.delete(line, changeStart + 1);
        setLine(text, kind, line);
    }

    /**
     * Keeps every list or quote span on one line of its own: a line broken
     * in two gives the new one the same, lines joined keep the first's.
     */
    private void lines(Editable text) {
        for (Class<?> type : new Class<?>[] {BulletSpan.class, NumberSpan.class, QuoteSpan.class}) {
            for (Object span : text.getSpans(0, text.length(), type)) {
                int start = text.getSpanStart(span);
                int end = text.getSpanEnd(span);
                if (start != lineStart(text, start)) {
                    text.removeSpan(span);
                    continue;
                }
                int lineEnd = lineEnd(text, start);
                if (end <= lineEnd) {
                    continue;
                }
                String kind = kindOf(span);
                text.setSpan(span, start, lineEnd, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                int line = lineEnd + 1;
                while (line <= end && line <= text.length()) {
                    setLine(text, kind, line);
                    int next = lineEnd(text, line);
                    if (next >= end) {
                        break;
                    }
                    line = next + 1;
                }
            }
        }
    }

    /** Sets one line's list or quote span, the newline left out. */
    private void setLine(Spannable text, String kind, int line) {
        text.setSpan(create(kind, null), line, lineEnd(text, line), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    /** One line's span of a list or quote kind, or null. */
    private static Object lineSpan(Spanned text, String kind, int line) {
        for (Object span : text.getSpans(line, line, typeOf(kind))) {
            if (text.getSpanStart(span) == line) {
                return span;
            }
        }
        return null;
    }

    /** The span of an inline kind holding the caret, or null. */
    private static Object at(Spanned text, String kind, int caret) {
        for (Object span : text.getSpans(caret, caret, typeOf(kind))) {
            if (!is(span, kind)) {
                continue;
            }
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            int points = text.getSpanFlags(span) & Spanned.SPAN_POINT_MARK_MASK;
            boolean grows =
                    points == Spanned.SPAN_INCLUSIVE_INCLUSIVE
                            || points == Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
            if (start < caret && caret < end
                    || start == end && start == caret
                    || caret == end && start < end && grows) {
                return span;
            }
        }
        return null;
    }

    /** Whether spans of a kind cover the whole range between them. */
    private static boolean covered(Spanned text, String kind, int start, int end) {
        int reached = start;
        boolean moved = true;
        while (reached < end && moved) {
            moved = false;
            for (Object span : text.getSpans(start, end, typeOf(kind))) {
                if (is(span, kind)
                        && text.getSpanStart(span) <= reached
                        && text.getSpanEnd(span) > reached) {
                    reached = text.getSpanEnd(span);
                    moved = true;
                }
            }
        }
        return reached >= end;
    }

    /** Takes a kind off a range, keeping it on either side. */
    private void strip(Editable text, String kind, int start, int end) {
        for (Object span : text.getSpans(start, end, typeOf(kind))) {
            int from = text.getSpanStart(span);
            int to = text.getSpanEnd(span);
            if (!is(span, kind) || to <= start || from >= end) {
                continue;
            }
            int flags = text.getSpanFlags(span);
            text.removeSpan(span);
            if (from < start) {
                text.setSpan(copy(span), from, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (to > end) {
                text.setSpan(copy(span), end, to, flags);
            }
        }
    }

    /** Ends a style at the caret: what is typed next is not in it. */
    private void split(Editable text, Object span, int caret) {
        int from = text.getSpanStart(span);
        int to = text.getSpanEnd(span);
        int flags = text.getSpanFlags(span);
        text.removeSpan(span);
        if (from < caret) {
            text.setSpan(copy(span), from, caret, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (caret < to) {
            text.setSpan(copy(span), caret, to, flags);
        }
    }

    /** A new span of a kind. */
    private Object create(String kind, String url) {
        return switch (kind) {
            case "strong" -> new StyleSpan(Typeface.BOLD);
            case "emphasis" -> new StyleSpan(Typeface.ITALIC);
            case "strike" -> new StrikethroughSpan();
            case "code" -> new CodeSpan(faint);
            case "link" -> new URLSpan(url);
            case "bullet" ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? new BulletSpan(margin / 2, getCurrentTextColor(), margin / 8)
                            : new BulletSpan(margin / 2, getCurrentTextColor());
            case "ordered" -> new NumberSpan(margin);
            case "quote" ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? new QuoteSpan(secondary, margin / 8, margin / 2)
                            : new QuoteSpan(secondary);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** A span like another, for the part of it a split keeps. */
    private Object copy(Object span) {
        return span instanceof URLSpan link ? new URLSpan(link.getURL()) : create(kindOf(span), null);
    }

    private static String kindOf(Object span) {
        if (span instanceof StyleSpan style) {
            return style.getStyle() == Typeface.ITALIC ? "emphasis" : "strong";
        }
        if (span instanceof StrikethroughSpan) {
            return "strike";
        }
        if (span instanceof CodeSpan) {
            return "code";
        }
        if (span instanceof URLSpan) {
            return "link";
        }
        if (span instanceof BulletSpan) {
            return "bullet";
        }
        if (span instanceof NumberSpan) {
            return "ordered";
        }
        return "quote";
    }

    private static Class<?> typeOf(String kind) {
        return switch (kind) {
            case "strong", "emphasis" -> StyleSpan.class;
            case "strike" -> StrikethroughSpan.class;
            case "code" -> CodeSpan.class;
            case "link" -> URLSpan.class;
            case "bullet" -> BulletSpan.class;
            case "ordered" -> NumberSpan.class;
            default -> QuoteSpan.class;
        };
    }

    private static boolean is(Object span, String kind) {
        return kindOf(span).equals(kind);
    }

    private static boolean block(String kind) {
        return "bullet".equals(kind) || "ordered".equals(kind) || "quote".equals(kind);
    }

    private static int lineStart(CharSequence text, int at) {
        return TextUtils.lastIndexOf(text, '\n', at - 1) + 1;
    }

    private static int lineEnd(CharSequence text, int line) {
        int end = TextUtils.indexOf(text, '\n', line);
        return end < 0 ? text.length() : end;
    }

    private void changed() {
        if (onState != null) {
            onState.run();
        }
    }

    /**
     * One link of the text.
     *
     * @param start where its text starts
     * @param end where it ends
     * @param text what it reads
     * @param url where it goes
     */
    public record Link(int start, int end, String text, String url) {}
}
