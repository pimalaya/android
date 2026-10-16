---
cairn: change
id: rich-composition
status: landed
created: 2026-10-16
---

# Rich composition: a Markdown editor, sent as HTML with the Markdown as plain twin

## Why

The composer writes plain text only (`rust/src/mail/compose.rs`, by design). Nobody can bold a word, put a link under text or write a list, and replies to HTML mail lose the original's structure. Design: the "Rich composition (proposed)" row of the Pimalaya Android canvas.

## What

- The body is Markdown, the one source of truth. The toolbar above the keyboard edits it directly: bold wraps the selection in `**`, italic in `_`, strikethrough in `~~`, code in `` ` ``, a link becomes `[text](url)` (from the link sheet), lists and quotes prefix the selected lines with `- `, `1. ` or `> `. Pressed again, it takes the markers off. Each button shows whether the caret sits inside its style.
- The editor styles the Markdown as it is typed: markers stay visible, dimmed; the text between them is shown bold, italic, struck, monospace, as a link, a list or a quote. A parse in Rust (`pulldown-cmark`, with strikethrough) returns UTF-16 ranges over JNI; Java applies the spans, no Markdown logic of its own.
- Sending compiles the Markdown into `multipart/alternative`: the Markdown source as text/plain, and HTML limited to `<p>`, `<strong>`, `<em>`, `<del>`, `<code>`, `<a>`, `<ul>`, `<ol>`, `<li>`, `<blockquote>`, `<br>`. HTML written in the Markdown is escaped, never passed through; anything else Markdown knows (headings, tables, images, code blocks) is rendered as its text.
- A reply converts the original to Markdown once, when the composer opens, and prefixes every line with `> ` under the intro line. An HTML original goes through Android's HTML reader into spans, then a Java writer turns them into Markdown, escaping Markdown characters in its text; a plain-text original is quoted as it is. A forward converts the same way and stays unquoted under its header block.
- Format per message, a "Rich / Plain" chip in the bar; the sheet previews the plain twin. Plain mode is today's composer, unchanged. The default is per account (rich); a reply follows the original (HTML original: rich, plain original: plain).

## Later

A raw view: the same Markdown with no styling, for writing it by hand.

## Out of scope

Raw HTML, inline images, attachments, signatures, drafts.
