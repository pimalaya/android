//! # Writer vectors
//!
//! pimdir's GTD writer vectors (conventions/gtd/writers.json), run
//! against the app's task writers: each case an input VTODO, an edit and
//! the bytes it writes, so the app and unfog edit a task alike.
//!
//! Each edit goes through [`write`] as the Tasks tab sends it (TaskEdit's
//! JSON), a capture through [`create`] then [`write`] as TaskWrites
//! composes one. The vectors are read from the pimdir checkout beside this
//! one, or from the directory `PIMALAYA_PIMDIR_VECTORS` names; without
//! either the run says so and passes over them.

use std::{fs, path::PathBuf};

use serde_json::{Value, json};

use crate::calendar::{
    edit::{EventDraft, create, write},
    task::task,
};

/// What an edit came to.
#[derive(Debug, PartialEq)]
enum Outcome {
    /// These bytes were written.
    Written(String),
    /// The task came back as it was: nothing to stage.
    Unchanged,
    /// The edit was refused.
    Refused,
}

/// The directory holding writers.json.
fn directory() -> PathBuf {
    match std::env::var_os("PIMALAYA_PIMDIR_VECTORS") {
        Some(dir) => PathBuf::from(dir),
        None => PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../pimdir/conventions/gtd"),
    }
}

/// The edit object the Tasks tab sends for one vector edit, at `now`.
fn edit_json(edit: &Value, now: &str) -> Value {
    match edit["op"].as_str().unwrap() {
        "set-state" => json!({ "gtd": edit["state"].as_str().unwrap_or(""), "stamp": now }),
        // NOTE: the form sends the person's categories comma-separated, as
        // TaskEdit carries them; no vector value holds a comma.
        "set-categories" => {
            let categories: Vec<&str> = edit["categories"]
                .as_array()
                .unwrap()
                .iter()
                .map(|value| value.as_str().unwrap())
                .collect();
            json!({ "categories": categories.join(","), "stamp": now })
        }
        "set-contact" => json!({
            "contact": {
                "name": edit["name"].as_str().unwrap_or(""),
                "address": edit["address"].as_str().unwrap_or(""),
            },
            "stamp": now,
        }),
        op => panic!("no writer for `{op}`"),
    }
}

/// Runs `edit` over `input` at `now` with the app's writer.
fn run(edit: &Value, input: &str, now: &str) -> Outcome {
    match write(input, &edit_json(edit, now).to_string()) {
        Ok(output) if output == input => Outcome::Unchanged,
        Ok(output) => Outcome::Written(output),
        Err(_) => Outcome::Refused,
    }
}

/// A capture as TaskWrites composes one: a new VTODO, then its edit.
fn capture(summary: &str, now: &str) -> String {
    let created = create(&EventDraft {
        component: "VTODO",
        uid: "capture@example.org",
        stamp: now,
        start: "",
        tzid: "",
        vtimezone: "",
    })
    .unwrap();
    let edit = json!({ "gtd": "inbox", "summary": summary, "stamp": now });
    write(&created, &edit.to_string()).unwrap()
}

/// The unfolded lines of the VTODO of `ical`.
fn vtodo_lines(ical: &str) -> Vec<String> {
    let text = ical.replace("\r\n ", "").replace("\r\n\t", "");
    let start = text.find("BEGIN:VTODO\r\n").unwrap() + "BEGIN:VTODO\r\n".len();
    let end = text.find("END:VTODO\r\n").unwrap();
    text[start..end]
        .split("\r\n")
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect()
}

#[test]
fn the_app_writes_as_pimdir_writer_vectors_say() {
    let dir = directory();
    let Ok(text) = fs::read_to_string(dir.join("writers.json")) else {
        eprintln!(
            "no pimdir writer vectors at {}; passing over them",
            dir.display()
        );
        return;
    };
    let vectors: Value = serde_json::from_str(&text).unwrap();
    let now = vectors["now"].as_str().unwrap();
    let read = |path: &Value| fs::read_to_string(dir.join(path.as_str().unwrap())).unwrap();

    let mut failures = Vec::new();
    let cases = vectors["cases"].as_array().unwrap();
    assert!(!cases.is_empty(), "no case in {}", dir.display());

    for case in cases {
        let label = case["label"].as_str().unwrap();
        let edit = &case["edit"];
        let expect = &case["expect"];

        match edit["op"].as_str().unwrap() {
            "capture" => {
                let lines = vtodo_lines(&capture(edit["summary"].as_str().unwrap(), now));
                for line in expect["vtodo"].as_array().unwrap() {
                    let line = line.as_str().unwrap();
                    if !lines.iter().any(|held| held == line) {
                        failures.push(format!("{label}: no `{line}` in {lines:?}"));
                    }
                }
                for name in expect["names"].as_array().unwrap() {
                    let name = format!("{}:", name.as_str().unwrap());
                    if !lines.iter().any(|held| held.starts_with(&name)) {
                        failures.push(format!("{label}: no {name} in {lines:?}"));
                    }
                }
            }
            "captured-at" => {
                let read = task(&read(&case["input"])).unwrap().unwrap().created;
                if read != expect["value"].as_str().unwrap() {
                    failures.push(format!("{label}: read {read}"));
                }
            }
            _ => {
                let outcome = run(edit, &read(&case["input"]), now);
                let wanted = match (&expect["bytes"], &expect["unchanged"], &expect["refused"]) {
                    (Value::String(_), _, _) => Outcome::Written(read(&expect["bytes"])),
                    (_, Value::Bool(true), _) => Outcome::Unchanged,
                    (_, _, Value::Bool(true)) => Outcome::Refused,
                    _ => panic!("{label}: no expectation"),
                };
                if outcome != wanted {
                    failures.push(format!(
                        "{label}:\n--- wanted\n{wanted:?}\n--- written\n{outcome:?}"
                    ));
                }
            }
        }
    }

    assert!(
        failures.is_empty(),
        "{} of {} cases failed:\n\n{}",
        failures.len(),
        cases.len(),
        failures.join("\n\n")
    );
}
