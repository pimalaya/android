//! # Markdown
//!
//! The composer's Markdown: the styled text its editor shows, and the HTML
//! a rich message sends beside it.
//!
//! The Markdown is the message, sent as written for its text/plain part.
//! The HTML is a closed set ([`html`]): paragraphs, emphasis, strong,
//! strikethrough, inline code, links, lists and quotes. Anything else
//! CommonMark knows (headings, code blocks, tables, images, rules) is sent
//! as its text, and HTML written in the Markdown is escaped, never passed
//! through.

use std::{cmp::Reverse, fmt::Write as _};

use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};
use serde::Serialize;

/// The link schemes the HTML keeps; any other link is sent as its text.
const SCHEMES: [&str; 3] = ["http://", "https://", "mailto:"];

/// A rich draft as its editor shows it: the text and the styles over it.
#[derive(Debug, PartialEq, Serialize)]
pub struct Rich {
    /// The text, without its Markdown syntax.
    pub text: String,
    /// The styles over it, outermost first.
    pub styles: Vec<Style>,
}

/// One styled range of the editor's text, in UTF-16 code units.
#[derive(Debug, PartialEq, Serialize)]
pub struct Style {
    /// What the range is: `strong`, `emphasis`, `strike`, `code`, `link`,
    /// `quote`, or a list item, `bullet` or `ordered`.
    pub kind: &'static str,
    /// Where it starts, as Java indexes a string.
    pub start: usize,
    /// Where it ends, exclusive.
    pub end: usize,
    /// A link's address.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
}

/// Whether the Markdown uses any of the closed set beyond paragraphs.
///
/// A message that does not is sent as its text alone: an HTML part
/// repeating it says nothing more.
pub fn formatted(markdown: &str) -> bool {
    Parser::new_ext(markdown, options()).any(|event| match event {
        Event::Code(_) => true,
        Event::Start(tag) => allowed(&tag) && !matches!(tag, Tag::Paragraph),
        _ => false,
    })
}

/// The Markdown as the HTML of a rich message's text/html part.
///
/// A line break is kept as one, as a message's author means it, where
/// CommonMark would join the lines.
pub fn html(markdown: &str) -> String {
    let mut out =
        String::from("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>\n");
    // NOTE: per open link, whether its tag was written, so a link
    // dropped for its scheme drops its closing tag too.
    let mut links: Vec<bool> = Vec::new();
    let mut skip: Option<(usize, TagEnd)> = None;

    for (event, range) in Parser::new_ext(markdown, options()).into_offset_iter() {
        if let Some((depth, end)) = &mut skip {
            match &event {
                Event::Start(tag) if tag.to_end() == *end => *depth += 1,
                Event::End(tag) if tag == end => match *depth {
                    0 => skip = None,
                    _ => *depth -= 1,
                },
                _ => (),
            }
            continue;
        }

        match event {
            Event::Start(tag) if !allowed(&tag) => {
                let source = &markdown[range];
                match tag {
                    Tag::Image { .. } => out.push_str(&escape(source)),
                    _ => {
                        out.push_str("<p>");
                        out.push_str(&escape(source.trim_end()).replace('\n', "<br>\n"));
                        out.push_str("</p>\n");
                    }
                }
                skip = Some((0, tag.to_end()));
            }
            Event::Start(Tag::Paragraph) => out.push_str("<p>"),
            Event::End(TagEnd::Paragraph) => out.push_str("</p>\n"),
            Event::Start(Tag::Emphasis) => out.push_str("<em>"),
            Event::End(TagEnd::Emphasis) => out.push_str("</em>"),
            Event::Start(Tag::Strong) => out.push_str("<strong>"),
            Event::End(TagEnd::Strong) => out.push_str("</strong>"),
            Event::Start(Tag::Strikethrough) => out.push_str("<del>"),
            Event::End(TagEnd::Strikethrough) => out.push_str("</del>"),
            Event::Start(Tag::Link { dest_url, .. }) => {
                let kept = SCHEMES
                    .iter()
                    .any(|scheme| dest_url.to_ascii_lowercase().starts_with(scheme));
                if kept {
                    let _ = write!(out, "<a href=\"{}\">", escape(&dest_url));
                }
                links.push(kept);
            }
            Event::End(TagEnd::Link) => {
                if links.pop().unwrap_or(false) {
                    out.push_str("</a>");
                }
            }
            Event::Start(Tag::List(None)) => out.push_str("<ul>\n"),
            Event::Start(Tag::List(Some(1))) => out.push_str("<ol>\n"),
            Event::Start(Tag::List(Some(start))) => {
                let _ = writeln!(out, "<ol start=\"{start}\">");
            }
            Event::End(TagEnd::List(false)) => out.push_str("</ul>\n"),
            Event::End(TagEnd::List(true)) => out.push_str("</ol>\n"),
            Event::Start(Tag::Item) => out.push_str("<li>"),
            Event::End(TagEnd::Item) => out.push_str("</li>\n"),
            Event::Start(Tag::BlockQuote(_)) => out.push_str("<blockquote>\n"),
            Event::End(TagEnd::BlockQuote(_)) => out.push_str("</blockquote>\n"),
            Event::Code(code) => {
                let _ = write!(out, "<code>{}</code>", escape(&code));
            }
            Event::SoftBreak | Event::HardBreak => out.push_str("<br>\n"),
            Event::Rule => {
                let _ = writeln!(out, "<p>{}</p>", escape(markdown[range].trim_end()));
            }
            Event::Text(text) | Event::Html(text) | Event::InlineHtml(text) => {
                out.push_str(&escape(&text));
            }
            _ => (),
        }
    }

    out.push_str("</body></html>\n");
    out
}

/// A rich draft's Markdown as its editor shows it.
///
/// The text without its syntax, the styles over it: what the HTML sends as
/// markup is styled, what it sends as text (a heading, a code block, typed
/// HTML) is shown as written, so the editor shows what goes out.
pub fn rich(markdown: &str) -> Rich {
    let mut view = View::default();
    let mut open: Vec<Open> = Vec::new();
    let mut lists: Vec<&'static str> = Vec::new();
    let mut skip: Option<(usize, TagEnd)> = None;

    for (event, range) in Parser::new_ext(markdown, options()).into_offset_iter() {
        if let Some((depth, end)) = &mut skip {
            match &event {
                Event::Start(tag) if tag.to_end() == *end => *depth += 1,
                Event::End(tag) if tag == end => match *depth {
                    0 => skip = None,
                    _ => *depth -= 1,
                },
                _ => (),
            }
            continue;
        }

        match event {
            Event::Start(tag) if !allowed(&tag) => {
                let source = &markdown[range];
                match tag {
                    Tag::Image { .. } => view.push(&mut open, source),
                    _ => {
                        view.push(&mut open, source.trim_end());
                        view.part("\n\n");
                    }
                }
                skip = Some((0, tag.to_end()));
            }
            Event::Start(tag) => {
                let (kind, url) = match tag {
                    Tag::List(start) => {
                        lists.push(if start.is_some() { "ordered" } else { "bullet" });
                        view.part("\n");
                        (None, None)
                    }
                    Tag::Item => {
                        view.part("\n");
                        (lists.last().copied(), None)
                    }
                    Tag::Strong => (Some("strong"), None),
                    Tag::Emphasis => (Some("emphasis"), None),
                    Tag::Strikethrough => (Some("strike"), None),
                    Tag::Link { dest_url, .. } => {
                        let kept = SCHEMES
                            .iter()
                            .any(|scheme| dest_url.to_ascii_lowercase().starts_with(scheme));
                        match kept {
                            true => (Some("link"), Some(dest_url.to_string())),
                            false => (None, None),
                        }
                    }
                    Tag::BlockQuote(_) => (Some("quote"), None),
                    _ => (None, None),
                };
                open.push(Open {
                    kind,
                    url,
                    start: None,
                });
            }
            Event::End(tag) => {
                match tag {
                    TagEnd::Paragraph | TagEnd::BlockQuote(_) => view.part("\n\n"),
                    TagEnd::List(_) => {
                        lists.pop();
                        view.part("\n\n");
                    }
                    TagEnd::Item => view.part("\n"),
                    _ => (),
                }
                let Some(element) = open.pop() else {
                    continue;
                };
                if let (Some(kind), Some(start)) = (element.kind, element.start) {
                    view.styles.push(Style {
                        kind,
                        start,
                        end: view.units,
                        url: element.url,
                    });
                }
            }
            Event::Code(code) => {
                view.push(&mut open, "");
                let start = view.units;
                view.push(&mut open, &code);
                view.styles.push(Style {
                    kind: "code",
                    start,
                    end: view.units,
                    url: None,
                });
            }
            Event::Text(text) | Event::Html(text) | Event::InlineHtml(text) => {
                view.push(&mut open, &text);
            }
            Event::SoftBreak | Event::HardBreak => view.push(&mut open, "\n"),
            Event::Rule => {
                view.push(&mut open, markdown[range].trim_end());
                view.part("\n\n");
            }
            _ => (),
        }
    }

    view.styles
        .sort_by_key(|style| (style.start, Reverse(style.end)));
    Rich {
        text: view.text,
        styles: view.styles,
    }
}

/// The editor's text as [`rich`] writes it.
#[derive(Default)]
struct View {
    text: String,
    /// The text's length in UTF-16 code units.
    units: usize,
    /// What parts the last block from the next, written with the next text
    /// so none trails the last.
    pending: &'static str,
    styles: Vec<Style>,
}

impl View {
    /// Writes some text, opening every element still waiting for its first.
    fn push(&mut self, open: &mut [Open], text: &str) {
        if !self.text.is_empty() {
            self.text.push_str(self.pending);
            self.units += self.pending.len();
        }
        self.pending = "";
        for element in open.iter_mut().filter(|element| element.start.is_none()) {
            element.start = Some(self.units);
        }
        self.text.push_str(text);
        self.units += text.encode_utf16().count();
    }

    /// Parts the next block from the last: a line, or a blank one.
    fn part(&mut self, separator: &'static str) {
        if separator.len() > self.pending.len() {
            self.pending = separator;
        }
    }
}

/// An element [`rich`] is inside of.
struct Open {
    /// Its style; none for one that only holds others.
    kind: Option<&'static str>,
    /// A link's address.
    url: Option<String>,
    /// Where its first text starts, once written.
    start: Option<usize>,
}

/// CommonMark with strikethrough, the one extension the toolbar writes.
fn options() -> Options {
    Options::ENABLE_STRIKETHROUGH
}

/// Whether a tag is of the closed set the HTML sends as markup.
fn allowed(tag: &Tag<'_>) -> bool {
    matches!(
        tag,
        Tag::Paragraph
            | Tag::Emphasis
            | Tag::Strong
            | Tag::Strikethrough
            | Tag::Link { .. }
            | Tag::List(_)
            | Tag::Item
            | Tag::BlockQuote(_)
    )
}

/// Text made safe inside HTML, attributes included.
fn escape(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for char in text.chars() {
        match char {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            '\'' => escaped.push_str("&#39;"),
            _ => escaped.push(char),
        }
    }
    escaped
}

#[cfg(test)]
mod tests {
    use crate::mail::markdown::*;

    fn body(markdown: &str) -> String {
        let html = html(markdown);
        let start = html.find("<body>\n").unwrap() + "<body>\n".len();
        let end = html.find("</body>").unwrap();
        html[start..end].to_string()
    }

    /// The editor's text, and each style as its kind over the text it covers.
    fn view(markdown: &str) -> (String, Vec<(&'static str, String)>) {
        let rich = rich(markdown);
        let text: Vec<u16> = rich.text.encode_utf16().collect();
        let styles = rich
            .styles
            .into_iter()
            .map(|style| {
                let slice = String::from_utf16(&text[style.start..style.end]).unwrap();
                (style.kind, slice)
            })
            .collect();
        (rich.text, styles)
    }

    #[test]
    fn the_closed_set_renders_as_markup() {
        assert_eq!(
            body("See **this** _now_ ~~or not~~ `x` [page](https://example.org)"),
            "<p>See <strong>this</strong> <em>now</em> <del>or not</del> <code>x</code> \
             <a href=\"https://example.org\">page</a></p>\n"
        );
        assert_eq!(
            body("- one\n- two\n\n3. three\n\n> said"),
            "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n<ol start=\"3\">\n<li>three</li>\n</ol>\n\
             <blockquote>\n<p>said</p>\n</blockquote>\n"
        );
    }

    #[test]
    fn a_line_break_stays_one() {
        assert_eq!(body("Hi Léa,\nthanks"), "<p>Hi Léa,<br>\nthanks</p>\n");
    }

    #[test]
    fn typed_html_is_escaped_and_loads_nothing() {
        let html =
            body("Look <img src=\"https://tracker.example/x.gif\"> here\n\n<script>x</script>");
        assert!(!html.contains("<img"));
        assert!(!html.contains("<script"));
        assert!(html.contains("&lt;img src=&quot;https://tracker.example/x.gif&quot;&gt;"));
        assert!(html.contains("&lt;script&gt;"));
    }

    #[test]
    fn what_the_closed_set_lacks_is_sent_as_its_text() {
        assert_eq!(body("# Title"), "<p># Title</p>\n");
        assert_eq!(
            body("![logo](https://example.org/logo.png)"),
            "<p>![logo](https://example.org/logo.png)</p>\n"
        );
        assert_eq!(
            body("```\nlet x;\n```"),
            "<p>```<br>\nlet x;<br>\n```</p>\n"
        );
        assert_eq!(body("a\n\n---\n\nb"), "<p>a</p>\n<p>---</p>\n<p>b</p>\n");
    }

    #[test]
    fn a_link_of_another_scheme_is_sent_as_its_text() {
        assert_eq!(body("[run](javascript:alert(1))"), "<p>run</p>\n");
        assert_eq!(
            body("[write](mailto:ada@example.org)"),
            "<p><a href=\"mailto:ada@example.org\">write</a></p>\n"
        );
    }

    #[test]
    fn escaped_markers_are_text() {
        assert_eq!(body("2\\*3\\*4"), "<p>2*3*4</p>\n");
        assert!(!formatted("2\\*3\\*4"));
    }

    #[test]
    fn formatting_is_told_from_plain_text() {
        assert!(!formatted("Hi,\n\nsee you.\n\n# not a heading we send"));
        assert!(formatted("Hi **you**"));
        assert!(formatted("`x`"));
        assert!(formatted("> quoted"));
        assert!(formatted("- item"));
    }

    #[test]
    fn the_editor_shows_the_text_without_its_syntax() {
        let (text, styles) = view("Hi **you**, _see_ ~~this~~ `x` [page](https://example.org)");
        assert_eq!(text, "Hi you, see this x page");
        assert_eq!(
            styles,
            [
                ("strong", "you".into()),
                ("emphasis", "see".into()),
                ("strike", "this".into()),
                ("code", "x".into()),
                ("link", "page".into()),
            ]
        );
        let link = rich("[page](https://example.org)").styles.remove(0);
        assert_eq!(link.url.as_deref(), Some("https://example.org"));
    }

    #[test]
    fn blocks_keep_their_lines_and_items_their_kind() {
        let (text, styles) = view("Points:\n\n- one\n- two\n\n1. three\n\nLéa\nAlex");
        assert_eq!(text, "Points:\n\none\ntwo\n\nthree\n\nLéa\nAlex");
        assert_eq!(
            styles,
            [
                ("bullet", "one".into()),
                ("bullet", "two".into()),
                ("ordered", "three".into()),
            ]
        );
    }

    #[test]
    fn quotes_nest() {
        let (text, styles) = view("> a\n>\n> > b");
        assert_eq!(text, "a\n\nb");
        assert_eq!(styles, [("quote", "a\n\nb".into()), ("quote", "b".into())]);
    }

    #[test]
    fn what_is_sent_as_text_is_shown_as_written() {
        assert_eq!(rich("# **Title**").text, "# **Title**");
        assert!(rich("# **Title**").styles.is_empty());
        assert_eq!(rich("2\\*3 <b>x</b>").text, "2*3 <b>x</b>");
        assert!(rich("[run](javascript:alert(1))").styles.is_empty());
    }

    #[test]
    fn styles_count_utf16_units() {
        let rich = rich("é😀 **b**");
        assert_eq!(rich.text, "é😀 b");
        assert_eq!((rich.styles[0].start, rich.styles[0].end), (4, 5));
    }
}
