---
cairn: log
change: rich-editor-styled
landed: 2026-10-16
---

# The rich editor shows styled text, no Markdown syntax

Capabilities moved: mail (MODIFIED A rich message is written in Markdown: styled on screen, Markdown at the edges).

Follow-up to rich-composition, asked on the device: visible markers suit a Markdown writer and nobody else. Hiding them inside the text was weighed and refused, since the caret and backspace would meet invisible characters. The editor now holds styled text: `markdown::rich` (replacing the marker ranges of `styles`, `Native.markdownRich`) reads Markdown into the shown text and its styles, which `MarkdownField` sets as spans (`StyleSpan`, `StrikethroughSpan`, `CodeSpan`, `URLSpan`, per-line `BulletSpan`, `NumberSpan`, `QuoteSpan`); `SpannedMarkdown` (HtmlMarkdown generalised) writes them back out on send and on a switch to plain, typed syntax escaped. Enter continues a list or quote and leaves it on an empty line; `- `, `1. ` or `> ` typed at a line's start starts one. The formatting bar was hidden by the keyboard under enforced edge-to-edge; the composer page now takes the keyboard inset. The format chip and its rich/plain dialog are taken out for now: an account's setting and the original's format decide. The composer's labels (To, Cc, Bcc, Subject) put the caret in their field. JVM tests: MarkdownFieldTest rewritten for the styled editor and its round trips; Rust tests cover `rich`. Not run on a device.
