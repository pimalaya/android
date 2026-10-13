//! # Push
//!
//! One batched push round of contact changes: what the Java driver hands
//! down, what each change answers, and the helpers the push paths share.

use serde::{Deserialize, Serialize};

use crate::error::BridgeError;

/// One change of a batched push round, handed down by the Java driver.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushChange {
    /// Opaque correlation key the outcome echoes back (the engine
    /// handle in practice).
    #[serde(rename = "ref")]
    pub reference: String,
    /// One of create, update, books (membership patch) or destroy.
    pub op: String,
    /// The card id addressed by update, books and destroy.
    pub id: Option<String>,
    /// The full vCard of create and update.
    pub vcard: Option<String>,
    /// The state last synced with the server, trimming update patches
    /// to the fields the edit changed.
    pub base_vcard: Option<String>,
    /// Book ids a books op adds the card to.
    #[serde(default)]
    pub add: Vec<String>,
    /// Book ids a books op removes the card from.
    #[serde(default)]
    pub remove: Vec<String>,
}

/// One change's outcome of a batched push round.
#[derive(Default, Serialize)]
pub struct PushOutcome {
    /// The correlation key of the change this outcome answers.
    #[serde(rename = "ref")]
    pub reference: String,
    /// Whether the server took the change; a rejected change is
    /// reconciled by the next sync instead of failing the round.
    pub accepted: bool,
    /// The server-assigned id of an accepted create.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// The revision of the accepted write, when the backend has one
    /// (the Graph changeKey; JMAP revisions only exist fetch-side).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub etag: Option<String>,
    /// What the server objected, on a rejected change (driver-side
    /// logging only).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// A required field of a push change, named in the error when absent.
pub fn required<'a>(
    field: &'a Option<String>,
    op: &str,
    name: &str,
) -> Result<&'a str, BridgeError> {
    field
        .as_deref()
        .ok_or_else(|| format!("Push {op} change is missing its {name}").into())
}

/// One rejected push outcome carrying what the server objected.
pub fn rejected(reference: String, error: String) -> PushOutcome {
    PushOutcome {
        reference,
        accepted: false,
        error: Some(error),
        ..Default::default()
    }
}
