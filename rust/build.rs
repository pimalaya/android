//! Build script: embeds the app's own shape of the pimdir statements, the
//! .sql files under pimdir/queries/, one per statement named after it.
//!
//! queries/read/, queries/queue/ and queries/owner/ hold the canonical
//! statements the app runs as text of its own (STORAGE §4.1, §4.4), sorted
//! by profile as io-pimdir's spec/ sorts them; queries/app/ holds the
//! statements of the app's own, which no profile names. A file's statements
//! run in order under its one name, in one transaction.

use std::{
    env, fs,
    path::{Path, PathBuf},
};

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let queries = root.join("pimdir/queries");
    println!("cargo:rerun-if-changed=pimdir");

    let mut out = String::new();
    out += "/// Every statement of the shape: its name, the directory it is filed\n";
    out += "/// under and its text.\n";
    out += "pub const SHAPE: &[(&str, &str, &str)] = &[\n";
    for profile in ["read", "queue", "owner", "app"] {
        for path in files(&queries.join(profile)) {
            let name = path.file_stem().unwrap().to_str().unwrap().to_uppercase();
            out += &format!(
                "    ({name:?}, {profile:?}, include_str!({:?})),\n",
                path.display()
            );
        }
    }
    out += "];\n";

    let target = PathBuf::from(env::var("OUT_DIR").unwrap()).join("shape.rs");
    fs::write(target, out).unwrap();
}

/// The .sql files of a directory, sorted by name; none when it is absent.
fn files(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut files: Vec<PathBuf> = entries
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "sql"))
        .collect();
    files.sort();
    files
}
