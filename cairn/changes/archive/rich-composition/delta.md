---
cairn: delta
change: rich-composition
---

## ADDED Requirements

### Requirement: A rich message is written in Markdown
The composer SHALL hold a rich message as Markdown, its toolbar editing the markers directly and the editor styling the text between them while showing the markers. Sending SHALL produce `multipart/alternative` with the Markdown as the text/plain part and an HTML part limited to paragraphs, strong, emphasis, strikethrough, inline code, links, lists, list items, quotes and line breaks. HTML written in the Markdown SHALL be escaped, never sent as HTML.

#### Scenario: A bold word and a link
- GIVEN a message reading `See **this** [page](https://example.org)`
- WHEN it is sent
- THEN its plain part reads the same, and its HTML part carries `<strong>this</strong>` and `<a href="https://example.org">page</a>`

#### Scenario: Typed HTML
- GIVEN a message containing `<img src="https://tracker.example/x.gif">`
- WHEN it is sent
- THEN the HTML part shows that text escaped and loads nothing

### Requirement: A reply quotes the original as Markdown
A reply SHALL convert the original to Markdown when the composer opens and quote every line with `> `; an HTML original SHALL keep its emphasis, links, lists and quotes, its own HTML never sent again.

#### Scenario: Replying to an HTML message
- GIVEN an HTML message with a bold word and a bulleted list
- WHEN it is replied to
- THEN the composer opens rich, with the original under `> ` as Markdown keeping the bold word and the list

### Requirement: The format is chosen per message
The composer SHALL show the message's format and let it be switched. A new message SHALL start in the account's default format; a reply SHALL start rich when the original has an HTML part and plain otherwise.

#### Scenario: Replying to a plain-text message
- GIVEN a message with a text/plain body only
- WHEN it is replied to
- THEN the composer opens in plain text

## MODIFIED Requirements

### Requirement: A message is composed to RFC 5322
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Bcc`, `Subject`, a reply's `In-Reply-To` and `References`, and a `text/plain; charset=utf-8` body, or for a rich message using formatting a `multipart/alternative` of that part and its `text/html` one. Header lines SHALL fold at 78 columns, `References` one msg-id per line, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76. Composing SHALL reach no server: it is a function of the fields alone.

## REMOVED Requirements
