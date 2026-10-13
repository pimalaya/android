//! # Fake HTTP server
//!
//! A [`Host`] answering HTTP/1.1 from a handler, for tests to drive the
//! real backend structs through the real [`Client`] I/O path.
//!
//! A test hands [`FakeServer::new`] a closure turning each request into a
//! response, runs a verb, then asserts on its answer and on
//! [`FakeServer::requests`]. Use `https` URLs: a credential never goes out
//! over plain `http`.
//!
//! It also holds the fixtures the backend tests share: a store answering a
//! mail step's `held` ([`holding`]) and a JMAP session ([`jmap_session`]).
//!
//! [`Client`]: crate::client::Client

use std::{
    collections::{HashMap, HashSet},
    time::Duration,
};

use io_jmap::rfc8620::session::JmapSession;
use jiff::Timestamp;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::{Value, json};
use url::Url;

use crate::{client::Host, error::BridgeError};

/// One request the client wrote, parsed.
#[derive(Clone, Debug)]
pub struct FakeRequest {
    /// The stream the request was written on.
    pub url: String,
    /// The request method.
    pub method: String,
    /// The target's path, as the request line writes it.
    pub path: String,
    /// The target's query pairs, decoded, in order.
    pub query: Vec<(String, String)>,
    /// The header fields, in order.
    pub headers: Vec<(String, String)>,
    /// The body, unchunked.
    pub body: Vec<u8>,
    /// The body as text, lossily decoded.
    pub text: String,
}

impl FakeRequest {
    /// The value of the header `name`, matched without case.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    /// The first value of the query parameter `key`.
    pub fn param(&self, key: &str) -> Option<&str> {
        self.query
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.as_str())
    }

    /// The body parsed as JSON, panicking when it is not.
    pub fn json<T: DeserializeOwned>(&self) -> T {
        serde_json::from_slice(&self.body).unwrap()
    }
}

/// One response the handler answers, written as HTTP/1.1.
#[derive(Clone, Debug)]
pub struct FakeResponse {
    /// The status code.
    pub status: u16,
    /// Headers besides `Content-Type` and `Content-Length`.
    pub headers: Vec<(String, String)>,
    /// The `Content-Type` header.
    pub content_type: &'static str,
    /// The body, framed by its `Content-Length`.
    pub body: Vec<u8>,
    /// Whether the connection drops before any answer byte, as a reset.
    pub lost: bool,
}

impl FakeResponse {
    /// A response carrying `body` as JSON.
    pub fn json(status: u16, body: &impl Serialize) -> Self {
        Self {
            status,
            headers: Vec::new(),
            content_type: "application/json",
            body: serde_json::to_vec(body).unwrap(),
            lost: false,
        }
    }

    /// A response with no body, as a `204 No Content` answers.
    pub fn empty(status: u16) -> Self {
        Self {
            status,
            headers: Vec::new(),
            content_type: "text/plain",
            body: Vec::new(),
            lost: false,
        }
    }

    /// An answer that never comes: the connection drops instead.
    pub fn lost() -> Self {
        Self {
            lost: true,
            ..Self::empty(500)
        }
    }

    /// The response as it goes on the wire.
    fn to_bytes(&self) -> Vec<u8> {
        let mut head = format!("HTTP/1.1 {} {}\r\n", self.status, reason(self.status));
        for (name, value) in &self.headers {
            head.push_str(&format!("{name}: {value}\r\n"));
        }
        head.push_str(&format!(
            "Content-Type: {}\r\nContent-Length: {}\r\n\r\n",
            self.content_type,
            self.body.len()
        ));

        let mut bytes = head.into_bytes();
        bytes.extend_from_slice(&self.body);
        bytes
    }
}

/// A server answering every request with its handler, its clock unslept.
pub struct FakeServer<F> {
    handler: F,
    /// Every request answered, in order.
    pub requests: Vec<FakeRequest>,
    /// Every wait the client took, the clock moving by it.
    pub slept: Vec<Duration>,
    /// The time [`Host::now`] answers.
    pub now: Timestamp,
    /// The store [`Host::serve`] asks, JSON in, JSON out; none fails.
    pub store: Option<FakeStore>,
    /// The bytes written per stream not yet parsed into a request.
    written: HashMap<String, Vec<u8>>,
    /// The response bytes per stream not yet read.
    answers: HashMap<String, Vec<u8>>,
    /// The streams whose next read fails, their answer lost.
    lost: HashSet<String>,
}

impl<F: FnMut(&FakeRequest) -> FakeResponse> FakeServer<F> {
    /// A server answering with `handler`.
    pub fn new(handler: F) -> Self {
        Self {
            handler,
            requests: Vec::new(),
            slept: Vec::new(),
            now: "2026-10-13T08:00:00Z".parse().unwrap(),
            store: None,
            written: HashMap::new(),
            answers: HashMap::new(),
            lost: HashSet::new(),
        }
    }

    /// Answers every complete request written on `url`.
    fn answer(&mut self, url: &str) {
        let buffer = self.written.entry(url.to_string()).or_default();
        while let Some((request, size)) = parse(url, buffer) {
            buffer.drain(..size);
            let response = (self.handler)(&request);
            if response.lost {
                self.lost.insert(url.to_string());
            } else {
                self.answers
                    .entry(url.to_string())
                    .or_default()
                    .extend(response.to_bytes());
            }
            self.requests.push(request);
        }
    }
}

/// A store answering [`Host::serve`] requests, JSON in, JSON out.
pub type FakeStore = Box<dyn FnMut(&str) -> String>;

/// A [`FakeServer`] over a boxed handler, a type a test helper can name.
pub type BoxedServer<'a> = FakeServer<Box<dyn FnMut(&FakeRequest) -> FakeResponse + 'a>>;

impl<'a> BoxedServer<'a> {
    /// A server answering with `handler`, boxed.
    pub fn boxed(handler: impl FnMut(&FakeRequest) -> FakeResponse + 'a) -> Self {
        Self::new(Box::new(handler))
    }
}

/// A store answering [`Client::held`](crate::client::Client::held) from `held`.
pub fn holding(held: HashSet<String>) -> FakeStore {
    Box::new(move |request| {
        let request: Value = serde_json::from_str(request).unwrap();
        assert_eq!(request["op"], "held");
        let handles: Vec<&str> = request["handles"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(Value::as_str)
            .filter(|handle| held.contains(*handle))
            .collect();
        json!({ "held": handles }).to_string()
    })
}

/// A JMAP session resource (RFC 8620 section 2) serving `capabilities`.
///
/// Its primary accounts are `primary`, its API at
/// `https://api.example.com/jmap/`, with no download or upload URL.
pub fn jmap_session(capabilities: Value, primary: Value) -> JmapSession {
    serde_json::from_value(json!({
        "username": "jane@example.com",
        "accounts": {},
        "primaryAccounts": primary,
        "capabilities": capabilities,
        "apiUrl": "https://api.example.com/jmap/",
        "downloadUrl": "",
        "uploadUrl": "",
        "eventSourceUrl": "",
        "state": "s1",
    }))
    .unwrap()
}

impl<F: FnMut(&FakeRequest) -> FakeResponse> Host for FakeServer<F> {
    fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        if self.lost.remove(url) {
            return Err("Connection reset".into());
        }
        Ok(self.answers.remove(url).unwrap_or_default())
    }

    fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        self.written
            .entry(url.to_string())
            .or_default()
            .extend_from_slice(bytes);
        self.answer(url);
        Ok(())
    }

    fn begin(&mut self, _url: &str) -> bool {
        false
    }

    fn settle(&mut self, _url: &str) {}

    fn reset(&mut self, url: &str) {
        self.written.remove(url);
        self.answers.remove(url);
    }

    fn starttls(&mut self, _url: &str) -> Result<(), BridgeError> {
        Err("No STARTTLS on a fake HTTP server".into())
    }

    fn serve(&mut self, request: &str) -> Result<String, BridgeError> {
        match &mut self.store {
            Some(store) => Ok(store(request)),
            None => Err("No store behind a fake HTTP server".into()),
        }
    }

    fn sleep(&mut self, delay: Duration) {
        self.slept.push(delay);
        self.now += delay;
    }

    fn now(&self) -> Timestamp {
        self.now
    }
}

/// The first complete request of `bytes` and its length, if complete.
fn parse(url: &str, bytes: &[u8]) -> Option<(FakeRequest, usize)> {
    let end = bytes.windows(4).position(|window| window == b"\r\n\r\n")? + 4;
    let head = String::from_utf8_lossy(&bytes[..end - 4]).into_owned();
    let mut lines = head.split("\r\n");

    let mut line = lines.next()?.split(' ');
    let method = line.next()?.to_string();
    let target = Url::parse(url).unwrap().join(line.next()?).unwrap();

    let headers: Vec<(String, String)> = lines
        .filter_map(|line| line.split_once(':'))
        .map(|(name, value)| (name.trim().to_string(), value.trim().to_string()))
        .collect();
    let named = |name: &str| {
        headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    };

    let (body, size) = if named("Transfer-Encoding").is_some_and(|value| value == "chunked") {
        unchunk(&bytes[end..]).map(|(body, size)| (body, end + size))?
    } else {
        let length: usize = named("Content-Length").map_or(0, |value| value.parse().unwrap());
        let body = bytes.get(end..end + length)?.to_vec();
        (body, end + length)
    };

    let request = FakeRequest {
        url: url.to_string(),
        method,
        path: target.path().to_string(),
        query: target.query_pairs().into_owned().collect(),
        text: String::from_utf8_lossy(&body).into_owned(),
        headers,
        body,
    };
    Some((request, size))
}

/// A chunked body decoded and its length, if its last chunk came.
fn unchunk(bytes: &[u8]) -> Option<(Vec<u8>, usize)> {
    let mut body = Vec::new();
    let mut at = 0;

    loop {
        let line = bytes[at..]
            .windows(2)
            .position(|window| window == b"\r\n")?;
        let size = String::from_utf8_lossy(&bytes[at..at + line]);
        let size = usize::from_str_radix(size.split(';').next()?.trim(), 16).unwrap();
        at += line + 2;
        if size == 0 {
            let trailer = bytes[at..]
                .windows(2)
                .position(|window| window == b"\r\n")?;
            return Some((body, at + trailer + 2));
        }
        body.extend_from_slice(bytes.get(at..at + size)?);
        at += size + 2;
    }
}

/// The reason phrase of a status (RFC 9110 section 15).
fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        201 => "Created",
        204 => "No Content",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        410 => "Gone",
        412 => "Precondition Failed",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "Unknown",
    }
}
