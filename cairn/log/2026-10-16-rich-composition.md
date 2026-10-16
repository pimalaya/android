---
cairn: log
change: rich-composition
landed: 2026-10-16
---

# Rich composition: a Markdown editor, sent as HTML with the Markdown as plain twin

Capabilities moved: mail (ADDED A rich message is written in Markdown, A reply quotes the original as Markdown, The format is chosen per message; MODIFIED A message is composed to RFC 5322, now `multipart/alternative` for a formatted rich message).

The composer was plain text only. A rich message is now Markdown, the one source of truth: `MarkdownField` keeps the markers visible and dims them, styling the text between from ranges `pulldown-cmark` returns over JNI (`Native.markdownStyles`, UTF-16 offsets), and the formatting bar above the keyboard edits the markers directly (bold, italic, strikethrough, code, links through a sheet, bulleted and numbered lists, quotes, clear). `compose.rs` sends a draft flagged `markdown` and using any formatting as `multipart/alternative`: the Markdown as written for text/plain, and `markdown::html`, a closed set (paragraphs, strong, emphasis, strikethrough, code, http/https/mailto links, lists, quotes, line breaks kept as `<br>`), with typed HTML escaped and headings, code blocks, tables, images and rules sent as their text.

A reply or forward follows the original: an HTML one opens rich, read by `Html.fromHtml` into spans and written as escaped Markdown by `HtmlMarkdown`, quoted with `> ` in a reply; a plain one stays plain, quoted as before. The app bar's format chip switches a message between rich and plain, the text kept as it is, and an account's settings choose how its new messages start (`ComposeFormat`, rich by default).

Design agreed in-session (Markdown instead of a custom document model, visible markers, no raw HTML) before the code; the canvas's rich composition row was redrawn to match. Rust tests cover the HTML, the styles and the two MIME shapes; JVM tests cover HtmlMarkdown and the bar's edits through the real bridge. Not run on a device.
