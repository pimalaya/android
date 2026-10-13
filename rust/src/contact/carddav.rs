//! # CardDAV
//!
//! The contact verbs of the [`Webdav`] backend: the RFC 6352 addressbook
//! and card verbs, and the RFC 6578 sync-collection round.

use std::{borrow::Cow, collections::BTreeSet};

use io_pim_discovery::rfc6764::{service::DiscoveryDavService, well_known::DiscoveryWellKnown};
use io_webdav::{
    rfc6352::{
        addressbook::{CarddavAddressbook as DavAddressbook, list::CarddavAddressbookList},
        card::{
            CarddavCardEntry,
            create::{CarddavCardCreate, CarddavCardCreateOk},
            delete::CarddavCardDelete,
            list::{CarddavCardList, CarddavCardListOptions},
            multiget::CarddavCardMultiget,
            read::CarddavCardRead,
            update::CarddavCardUpdate,
        },
    },
    rfc6578::sync_collection::WebdavSyncDelta,
};
use url::Url;
use vcard::tree::cst::VcardCst;

use crate::{
    client::{Host, USER_AGENT, webdav::Webdav},
    contact::{Addressbook, Card},
    error::BridgeError,
};

/// CardDAV addressbook and card verbs.
impl<H: Host> Webdav<'_, H> {
    /// Lists the account's addressbooks, from the principal's home set.
    ///
    /// Doubles as the connection check during onboarding: any failure
    /// (TLS, auth, discovery walk) surfaces here.
    pub fn list_addressbooks(&mut self, base_url: &Url) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = self.auth.webdav();

        // NOTE: RFC 6764 §5: a bare origin is not necessarily the
        // context root, so probe .well-known/carddav for the real one
        // (fastmail 404s outside /dav/*); no redirect keeps the origin.
        let base_url = match base_url.path() {
            "" | "/" => {
                let probe = DiscoveryWellKnown::new(base_url.clone(), DiscoveryDavService::Carddav);
                match self.client.run_discovery(probe) {
                    Ok(Some(root)) => root,
                    _ => base_url.clone(),
                }
            }
            _ => base_url.clone(),
        };
        let base_url = &base_url;

        // NOTE: some servers expose the home-set off the context root,
        // so a missing principal is not fatal.
        let principal = self
            .client
            .principal(base_url, &auth)?
            .unwrap_or_else(|| base_url.clone());

        let home_set = self
            .client
            .addressbook_home_set(&principal, &auth)?
            .ok_or_else(|| "No addressbook home set found".to_string())?;

        let coroutine = CarddavAddressbookList::new(&home_set, &auth, USER_AGENT, home_set.path());
        let addressbooks: BTreeSet<DavAddressbook> = self.client.run(&home_set, coroutine)?;

        Ok(addressbooks
            .into_iter()
            .map(|book| into_addressbook(&home_set, book))
            .collect())
    }

    /// Lists the cards of the addressbook collection at `url`.
    pub fn list_cards(&mut self, url: &Url) -> Result<Vec<Card>, BridgeError> {
        let auth = self.auth.webdav();
        let opts = CarddavCardListOptions::default();
        let coroutine = CarddavCardList::new(url, &auth, USER_AGENT, url.path(), &opts);
        let list = self.client.run(url, coroutine)?;

        // NOTE: a truncated listing would read as deletions downstream.
        if list.truncated {
            return Err(format!("CardDAV server truncated the card listing of {url}").into());
        }

        Ok(list.cards.into_iter().map(into_card).collect())
    }

    /// Creates the card named `id` in the collection at `url`, returned
    /// under the name the server filed it by ([`into_created_card`]).
    pub fn create_card(&mut self, url: &Url, id: &str, vcard: &str) -> Result<Card, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CarddavCardCreate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            normalize_vcard(vcard).as_bytes().to_vec(),
        );

        Ok(into_created_card(self.client.run(url, coroutine)?, vcard))
    }

    /// Reads the card at resource name `uri` in the collection at `url`.
    pub fn read_card(&mut self, url: &Url, uri: &str) -> Result<Card, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CarddavCardRead::new(url, &auth, USER_AGENT, url.path(), uri);
        let body = self.client.run(url, coroutine)?;

        Ok(Card {
            id: uri.trim_end_matches(".vcf").to_string(),
            uri: uri.to_string(),
            etag: body.etag,
            vcard: String::from_utf8_lossy(&body.data).into_owned(),
            books: Vec::new(),
        })
    }

    /// Updates the card at resource name `uri` in the collection at `url`,
    /// guarded by `if_match`, returning the new ETag if the server sent it.
    pub fn update_card(
        &mut self,
        url: &Url,
        uri: &str,
        vcard: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CarddavCardUpdate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            uri,
            normalize_vcard(vcard).as_bytes().to_vec(),
            if_match,
        );

        Ok(self.client.run(url, coroutine)?.etag)
    }

    /// Deletes the card at resource name `uri` in the collection at `url`,
    /// guarded by `if_match`.
    pub fn delete_card(
        &mut self,
        url: &Url,
        uri: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CarddavCardDelete::new(url, &auth, USER_AGENT, url.path(), uri, if_match);
        self.client.run(url, coroutine)?;

        Ok(())
    }

    /// Runs a sync-collection REPORT (RFC 6578) on the collection at `url`.
    ///
    /// A server without it is listed by PROPFIND, with no token: fine for
    /// an initial round, [`None`] for one with a cursor, as for a rejected
    /// token, so the caller re-runs it as an initial one.
    pub fn sync_cards(
        &mut self,
        url: &Url,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        self.sync_collection(url, sync_token)
    }

    /// Fetches the cards at the given resource names in the collection at
    /// `url`, by an addressbook-multiget REPORT.
    pub fn multiget_cards(&mut self, url: &Url, uris: &[&str]) -> Result<Vec<Card>, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CarddavCardMultiget::new(url, &auth, USER_AGENT, url.path(), uris);
        let cards: Vec<CarddavCardEntry> = self.client.run(url, coroutine)?;

        Ok(cards.into_iter().map(into_card).collect())
    }
}

/// Fills the properties an outgoing vCard's version requires.
///
/// iCloud and Fastmail reject a vCard 3.0 without `N`, which a contact
/// with only a display name lacks. A valid card is left untouched, and a
/// body that will not parse is sent as-is rather than dropped.
pub fn normalize_vcard(vcard: &str) -> Cow<'_, str> {
    match VcardCst::parse(vcard) {
        Ok(mut card) => Cow::Owned(card.fill_required().to_string()),
        Err(_) => Cow::Borrowed(vcard),
    }
}

/// An io-webdav addressbook as an [`Addressbook`].
///
/// The name defaults to the id, and the collection URL is rebuilt from
/// the home set, io-webdav keeping only the last path segment.
fn into_addressbook(home_set: &Url, book: DavAddressbook) -> Addressbook {
    let mut url = home_set.clone();
    url.set_path(&format!(
        "{}/{}/",
        home_set.path().trim_end_matches('/'),
        book.id
    ));

    Addressbook {
        name: book.display_name.unwrap_or_else(|| book.id.clone()),
        id: book.id,
        url: url.to_string(),
        description: book.description,
        color: book.color,
        // NOTE: CardDAV states no default; io-webdav reads no RFC 3744
        // privileges, so writable.
        role: String::new(),
        writable: true,
    }
}

/// A created card under the name the server filed it by.
///
/// That is the name asked for or the `Location` answered: Google files
/// under its own, and the name asked for would make the delete 404 (read
/// as converged) and the next listing bring the card back.
fn into_created_card(created: CarddavCardCreateOk, vcard: &str) -> Card {
    Card {
        id: created.id.clone(),
        uri: created.id,
        etag: created.etag,
        vcard: vcard.to_string(),
        books: Vec::new(),
    }
}

/// An io-webdav card entry as a [`Card`], its bytes decoded lossily.
fn into_card(entry: CarddavCardEntry) -> Card {
    Card {
        vcard: String::from_utf8_lossy(&entry.data).into_owned(),
        id: entry.id.clone(),
        uri: entry.id,
        etag: entry.etag,
        books: Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    //! The CardDAV create over a canned reply: the card is kept under the
    //! name the server filed it by, which is what a later delete addresses.

    use io_webdav::{
        coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
        rfc4918::WebdavAuth,
    };

    use crate::contact::carddav::*;

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

        let WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(request)) =
            coroutine.resume(None)
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
}
