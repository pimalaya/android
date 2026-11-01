//! # Blobs
//!
//! The one write of the blob store the Java owner cannot make itself: a
//! move that never replaces a name (STORAGE §5).
//!
//! The no-replace write used to link the temporary to its name and unlink
//! it. Android's SELinux policy denies `link` to an untrusted app on its
//! own data files, so every body write failed on the device while the
//! host, which allows hard links, passed every test; a rename is allowed.
//! Java has no rename that refuses an existing target atomically, hence
//! this export. Where the kernel or the file system lacks that rename, the
//! name is created exclusively and the bytes copied into it ([`claimed`]):
//! never `rename(2)`, which replaces.

use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    os::unix::fs::{MetadataExt, OpenOptionsExt},
    path::Path,
};

/// What a no-replace rename did.
#[derive(Debug, Eq, PartialEq)]
pub enum Filed {
    /// The source now holds the name.
    Moved,
    /// The name was already held, and kept: the source is where it was.
    Held,
}

/// How many times [`claimed`] files again a name moved away while it
/// copied.
const CLAIMS_MAX: usize = 3;

/// Moves `from` to `to` unless `to` exists, never replacing it:
/// `renameat2(RENAME_NOREPLACE)`, atomically. A name held answers
/// [`Filed::Held`], the caller deleting the source. A kernel or a file
/// system without the flag (`ENOSYS`, `EINVAL`) falls back to
/// [`claimed`].
pub fn rename_noreplace(from: &Path, to: &Path) -> io::Result<Filed> {
    file(from, to, renameat2)
}

/// [`rename_noreplace`] where the platform lets the app call `renameat2`,
/// [`claimed`] where it may not. Before API 30 bionic has no `renameat2`
/// and the app's seccomp filter, generated from bionic's own system calls,
/// need not allow it: a blocked call kills the process with `SIGSYS` rather
/// than failing, so the fallback could never run.
pub fn rename_noreplace_on(from: &Path, to: &Path, renameat2_allowed: bool) -> io::Result<Filed> {
    match renameat2_allowed {
        true => rename_noreplace(from, to),
        false => claimed(from, to),
    }
}

/// [`rename_noreplace`] through `rename`, a rename refusing a name held.
fn file(
    from: &Path,
    to: &Path,
    rename: impl FnOnce(&Path, &Path) -> io::Result<()>,
) -> io::Result<Filed> {
    match rename(from, to) {
        Ok(()) => Ok(Filed::Moved),
        Err(err) if err.kind() == io::ErrorKind::AlreadyExists => Ok(Filed::Held),
        Err(err) if unsupported(&err) => claimed(from, to),
        Err(err) => Err(err),
    }
}

/// Files `from`'s bytes under `to` by creating `to` exclusively
/// (`O_CREAT | O_EXCL`, mode `0600`), copying them into it and syncing it,
/// then unlinking `from`. The exclusive create fails on any name held, a
/// link included, so nothing is ever replaced, where no rename refuses a
/// name held and `link(2)` is denied (STORAGE §5).
///
/// Not atomic: until the copy is synced, the name holds part of the bytes.
/// A reader checks a body against its name (§7), so a part is never
/// served, only moved into quarantine/. A copy failing unlinks the part it
/// wrote, while the name still holds it; one whose name was moved away
/// meanwhile is filed again. A crash leaves the part under the name and
/// the source, a writer's temporary.
pub(crate) fn claimed(from: &Path, to: &Path) -> io::Result<Filed> {
    let mut source = File::open(from)?;
    for _ in 0..CLAIMS_MAX {
        let mut target = match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(to)
        {
            Ok(target) => target,
            Err(err) if err.kind() == io::ErrorKind::AlreadyExists => return Ok(Filed::Held),
            Err(err) => return Err(err),
        };
        let ours = target.metadata()?;
        source.seek(SeekFrom::Start(0))?;
        if let Err(err) = copy(&mut source, &mut target).and_then(|()| target.sync_all()) {
            if holds(to, &ours) {
                let _ = fs::remove_file(to);
            }
            return Err(err);
        }
        if holds(to, &ours) {
            // NOTE: the body is filed; a source left behind is the
            // caller's to delete, as it deletes it after every move.
            let _ = fs::remove_file(from);
            return Ok(Filed::Moved);
        }
    }
    Err(io::Error::other(format!(
        "{} was moved away while its bytes were copied",
        to.display()
    )))
}

/// Copies what `source` holds into `target` a buffer at a time, a plain
/// read and write: the fallback runs on old kernels, where `io::copy`
/// between two files would try `copy_file_range` first.
fn copy(source: &mut File, target: &mut File) -> io::Result<()> {
    let mut buffer = vec![0; 64 * 1024];
    loop {
        match source.read(&mut buffer) {
            Ok(0) => return Ok(()),
            Ok(read) => target.write_all(&buffer[..read])?,
            Err(err) if err.kind() == io::ErrorKind::Interrupted => {}
            Err(err) => return Err(err),
        }
    }
}

/// Whether `path` names the file `ours` describes, not following a link.
fn holds(path: &Path, ours: &Metadata) -> bool {
    fs::symlink_metadata(path)
        .is_ok_and(|held| held.dev() == ours.dev() && held.ino() == ours.ino())
}

/// Whether a failed `renameat2` says the kernel or the file system lacks
/// it rather than refusing this rename.
fn unsupported(err: &io::Error) -> bool {
    err.kind() == io::ErrorKind::Unsupported
        || matches!(err.raw_os_error(), Some(libc::ENOSYS | libc::EINVAL))
}

/// `renameat2(AT_FDCWD, from, AT_FDCWD, to, RENAME_NOREPLACE)` as a raw
/// system call: bionic exports `renameat2` from API 30 alone, the app's
/// floor is 26, and the kernel has had it since 3.15.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn renameat2(from: &Path, to: &Path) -> io::Result<()> {
    use std::{ffi::CString, os::unix::ffi::OsStrExt};

    let from = CString::new(from.as_os_str().as_bytes())?;
    let to = CString::new(to.as_os_str().as_bytes())?;
    // SAFETY: two NUL-terminated paths, alive for the whole call, and the
    // arguments `renameat2` takes, in its order.
    let done = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            from.as_ptr(),
            libc::AT_FDCWD,
            to.as_ptr(),
            libc::RENAME_NOREPLACE as libc::c_uint,
        )
    };
    match done {
        0 => Ok(()),
        _ => Err(io::Error::last_os_error()),
    }
}

/// No `renameat2` outside Linux: the exclusive create runs.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn renameat2(_from: &Path, _to: &Path) -> io::Result<()> {
    Err(io::ErrorKind::Unsupported.into())
}

#[cfg(test)]
mod tests {
    use std::fs;

    use crate::offline::blobs::{Filed, claimed, file, rename_noreplace, rename_noreplace_on};

    /// Writers racing for one name through the fallback: one files it, every
    /// other is told it is held, and what holds the name is the bytes of the
    /// one that filed it, never replaced (STORAGE §5).
    #[test]
    fn racing_writers_never_replace_through_the_fallback() {
        use std::sync::{Arc, Barrier};

        const WRITERS: usize = 8;
        for round in 0..200 {
            let dir = tempfile::tempdir().unwrap();
            let to = dir.path().join("x");
            let barrier = Arc::new(Barrier::new(WRITERS));
            let writers: Vec<_> = (0..WRITERS)
                .map(|writer| {
                    let from = dir.path().join(format!(".x.{writer}.tmp"));
                    fs::write(&from, format!("writer {writer}")).unwrap();
                    let (to, barrier) = (to.clone(), barrier.clone());
                    std::thread::spawn(move || {
                        barrier.wait();
                        (writer, claimed(&from, &to).unwrap())
                    })
                })
                .collect();
            let filed: Vec<usize> = writers
                .into_iter()
                .map(|writer| writer.join().unwrap())
                .filter(|(_, filed)| *filed == Filed::Moved)
                .map(|(writer, _)| writer)
                .collect();

            assert_eq!(filed.len(), 1, "round {round}: {filed:?} filed the name");
            let held = format!("writer {}", filed[0]);
            assert_eq!(fs::read_to_string(&to).unwrap(), held, "round {round}");
        }
    }

    #[test]
    fn a_free_name_takes_the_source() {
        let dir = tempfile::tempdir().unwrap();
        let (from, to) = (dir.path().join(".x.tmp"), dir.path().join("x"));
        fs::write(&from, b"body").unwrap();

        assert_eq!(rename_noreplace(&from, &to).unwrap(), Filed::Moved);
        assert_eq!(fs::read(&to).unwrap(), b"body");
        assert!(!from.exists());
    }

    /// A name held keeps what holds it, the source left for the caller to
    /// delete (STORAGE §5).
    #[test]
    fn a_held_name_is_never_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let (from, to) = (dir.path().join(".x.tmp"), dir.path().join("x"));
        fs::write(&from, b"new").unwrap();
        fs::write(&to, b"held").unwrap();

        assert_eq!(rename_noreplace(&from, &to).unwrap(), Filed::Held);
        assert_eq!(fs::read(&to).unwrap(), b"held");
        assert_eq!(fs::read(&from).unwrap(), b"new");

        fs::write(dir.path().join("x2"), b"held").unwrap();
        assert_eq!(claimed(&from, &dir.path().join("x2")).unwrap(), Filed::Held);
        assert_eq!(fs::read(dir.path().join("x2")).unwrap(), b"held");
        assert_eq!(
            claimed(&from, &dir.path().join("x3")).unwrap(),
            Filed::Moved
        );
        assert_eq!(fs::read(dir.path().join("x3")).unwrap(), b"new");
    }

    /// A link under the name is a name held, never followed nor replaced.
    #[cfg(unix)]
    #[test]
    fn a_link_holding_the_name_is_kept() {
        let dir = tempfile::tempdir().unwrap();
        let (from, to) = (dir.path().join(".x.tmp"), dir.path().join("x"));
        fs::write(&from, b"new").unwrap();
        std::os::unix::fs::symlink(dir.path().join("nowhere"), &to).unwrap();

        assert_eq!(rename_noreplace(&from, &to).unwrap(), Filed::Held);
        assert_eq!(claimed(&from, &to).unwrap(), Filed::Held);
        assert!(fs::symlink_metadata(&to).unwrap().file_type().is_symlink());
    }

    #[test]
    fn a_missing_source_is_an_error() {
        let dir = tempfile::tempdir().unwrap();
        let missing = dir.path().join("gone");
        assert!(rename_noreplace(&missing, &dir.path().join("x")).is_err());
        assert!(claimed(&missing, &dir.path().join("x")).is_err());
        assert!(!dir.path().join("x").exists(), "no name claimed");
    }

    /// A platform whose app seccomp filter need not allow `renameat2`
    /// (before API 30) files the body by the exclusive create without
    /// calling it; from API 30 the rename runs.
    #[test]
    fn a_platform_forbidding_renameat2_files_by_an_exclusive_create() {
        use std::os::unix::fs::MetadataExt;

        let dir = tempfile::tempdir().unwrap();
        let (from, to) = (dir.path().join(".y.tmp"), dir.path().join("y"));
        fs::write(&from, b"body").unwrap();
        let source = fs::metadata(&from).unwrap().ino();

        // NOTE: a rename keeps the source's inode; the exclusive create
        // makes a file of its own, so the inode tells which move ran.
        assert_eq!(
            rename_noreplace_on(&from, &to, false).unwrap(),
            Filed::Moved
        );
        assert_ne!(fs::metadata(&to).unwrap().ino(), source, "renamed");
        assert_eq!(fs::read(&to).unwrap(), b"body");
        assert!(!from.exists());

        fs::write(&from, b"new").unwrap();
        let source = fs::metadata(&from).unwrap().ino();
        fs::remove_file(&to).unwrap();
        assert_eq!(rename_noreplace_on(&from, &to, true).unwrap(), Filed::Moved);
        assert_eq!(fs::metadata(&to).unwrap().ino(), source, "copied");
    }

    /// A kernel or a file system refusing `RENAME_NOREPLACE` (`ENOSYS`,
    /// `EINVAL`) files the body by an exclusive create and a copy, never a
    /// rename: the name is a file of its own, `0600`, holding every byte,
    /// the source gone; a name held is kept, the source left.
    #[test]
    fn an_unsupported_rename_files_by_an_exclusive_create() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};

        let body: Vec<u8> = (0..200_000u32).map(|n| (n % 251) as u8).collect();
        for errno in [libc::ENOSYS, libc::EINVAL] {
            let dir = tempfile::tempdir().unwrap();
            let (from, to) = (dir.path().join(".x.tmp"), dir.path().join("x"));
            fs::write(&from, &body).unwrap();
            let source = fs::metadata(&from).unwrap().ino();
            let unsupported = |_: &std::path::Path, _: &std::path::Path| {
                Err(std::io::Error::from_raw_os_error(errno))
            };

            assert_eq!(file(&from, &to, unsupported).unwrap(), Filed::Moved);
            let filed = fs::symlink_metadata(&to).unwrap();
            assert_ne!(filed.ino(), source, "{errno}: renamed");
            assert_eq!(filed.permissions().mode() & 0o777, 0o600);
            assert_eq!(fs::read(&to).unwrap(), body);
            assert!(!from.exists(), "{errno}: the source stays");

            fs::write(&from, b"new").unwrap();
            assert_eq!(file(&from, &to, unsupported).unwrap(), Filed::Held);
            assert_eq!(fs::read(&to).unwrap(), body);
            assert_eq!(fs::read(&from).unwrap(), b"new");
        }
    }
}
