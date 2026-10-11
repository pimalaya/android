//! The CalDAV create over a canned reply: the event is kept under the
//! name the server filed it by, which is what a later delete addresses.

use io_webdav::coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield};

use super::*;

/// Runs a create against one reply, answering the event the bridge
/// keeps.
fn create(name: &str, reply: &str) -> EventRef {
    let base: Url = "https://apidata.googleusercontent.com/".parse().unwrap();
    let mut coroutine = CaldavItemCreate::new(
        &base,
        &WebdavAuth::None,
        USER_AGENT,
        "/caldav/v2/google%40pimalaya.org/events/",
        name,
        b"BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".to_vec(),
    );

    let WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(_)) = coroutine.resume(None) else {
        panic!("no request");
    };
    let WebdavCoroutineState::Yielded(WebdavYield::WantsRead) = coroutine.resume(None) else {
        panic!("no read");
    };
    let WebdavCoroutineState::Complete(Ok(created)) = coroutine.resume(Some(reply.as_bytes()))
    else {
        panic!("no event");
    };

    into_created_event(created)
}

/// Google files the event under a name of its own and says so: that name
/// is the event's, so its delete reaches it rather than a 404.
#[test]
fn an_event_google_files_elsewhere_is_kept_under_googles_name() {
    let created = create(
        "3f2a.ics",
        "HTTP/1.1 201 Created\r\n\
         Location: /caldav/v2/google%40pimalaya.org/events/k9q2r1.ics\r\n\
         ETag: \"e-1\"\r\n\
         Content-Length: 0\r\n\r\n",
    );

    assert_eq!(created.id, "k9q2r1.ics");
    assert_eq!(created.etag.as_deref(), Some("e-1"));
}

/// A server keeping the name asked for files the event under it.
#[test]
fn an_event_filed_as_asked_is_kept_under_the_name_asked() {
    let created = create(
        "3f2a.ics",
        "HTTP/1.1 201 Created\r\nETag: \"e-1\"\r\nContent-Length: 0\r\n\r\n",
    );

    assert_eq!(created.id, "3f2a.ics");
}
