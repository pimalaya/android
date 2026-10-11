//! The CardDAV create over a canned reply: the card is kept under the
//! name the server filed it by, which is what a later delete addresses.

use io_webdav::coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield};

use super::*;

/// Runs a create against one reply, answering the request line and the
/// card the bridge keeps.
fn create(name: &str, reply: &str) -> (String, Card) {
    let base: Url = "https://www.googleapis.com/".parse().unwrap();
    let mut coroutine = CarddavCardCreate::new(
        &base,
        &WebdavAuth::None,
        USER_AGENT,
        "/carddav/v1/principals/google@pimalaya.org/lists/default/",
        name,
        b"BEGIN:VCARD\r\nEND:VCARD\r\n".to_vec(),
    );

    let WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(request)) = coroutine.resume(None)
    else {
        panic!("no request");
    };
    let line = String::from_utf8(request).unwrap();
    let line = line.lines().next().unwrap().to_owned();
    let WebdavCoroutineState::Yielded(WebdavYield::WantsRead) = coroutine.resume(None) else {
        panic!("no read");
    };
    let WebdavCoroutineState::Complete(Ok(created)) = coroutine.resume(Some(reply.as_bytes()))
    else {
        panic!("no card");
    };

    (
        line,
        into_created_card(created, "BEGIN:VCARD\r\nEND:VCARD\r\n"),
    )
}

/// Google files the card under a name of its own and says so: that name
/// is the card's, so its delete reaches it rather than a 404.
#[test]
fn a_card_google_files_elsewhere_is_kept_under_googles_name() {
    let (line, card) = create(
        "3f2a.vcf",
        "HTTP/1.1 201 Created\r\n\
         Location: https://www.googleapis.com/carddav/v1/principals/google@pimalaya.org/lists/default/c7e1d\r\n\
         ETag: \"e-1\"\r\n\
         Content-Length: 0\r\n\r\n",
    );

    assert!(line.contains("/lists/default/3f2a.vcf "), "{line}");
    assert_eq!(card.uri, "c7e1d");
    assert_eq!(card.id, "c7e1d");
    assert_eq!(card.etag.as_deref(), Some("e-1"));
}

/// A server keeping the name asked for files the card under it, whole:
/// the name already carries its extension, so none is added.
#[test]
fn a_card_filed_as_asked_is_kept_under_the_name_asked() {
    let (_, card) = create(
        "3f2a.vcf",
        "HTTP/1.1 201 Created\r\nETag: \"e-1\"\r\nContent-Length: 0\r\n\r\n",
    );

    assert_eq!(card.uri, "3f2a.vcf");
    assert_eq!(card.id, "3f2a.vcf");
}
