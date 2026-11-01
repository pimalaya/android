//! # Blobs
//!
//! The one write of the blob store the Java owner cannot make itself: a
//! rename that never replaces a name (STORAGE §5).
//!
//! The no-replace write used to link the temporary to its name and unlink
//! it. Android's SELinux policy denies `link` to an untrusted app on its
//! own data files, so every body write failed on the device while the
//! host, which allows hard links, passed every test; a rename is allowed.
//! Java has no rename that refuses an existing target atomically, hence
//! this export.

use std::{fs, io, path::Path};

/// What a no-replace rename did.
#[derive(Debug, Eq, PartialEq)]
pub enum Filed {
    /// The source now holds the name.
    Moved,
    /// The name was already held, and kept: the source is where it was.
    Held,
}

/// Renames `from` to `to` unless `to` exists, never replacing it:
/// `renameat2(RENAME_NOREPLACE)`, atomically. A name held answers
/// [`Filed::Held`], the caller deleting the source. A kernel or a file
/// system without the flag (`ENOSYS`, `EINVAL`) falls back to
/// [`checked`].
pub fn rename_noreplace(from: &Path, to: &Path) -> io::Result<Filed> {
    match renameat2(from, to) {
        Ok(()) => Ok(Filed::Moved),
        Err(err) if err.kind() == io::ErrorKind::AlreadyExists => Ok(Filed::Held),
        Err(err) if unsupported(&err) => checked(from, to),
        Err(err) => Err(err),
    }
}

/// Renames `from` to `to` after checking `to` is not there, a link
/// included.
///
/// Racy: a writer filing `to` between the check and the rename has its
/// file replaced. Only the fallback where `renameat2` is missing, and two
/// writers of one name write the same bytes (the name is the content), so
/// the replaced file held what replaces it.
pub(crate) fn checked(from: &Path, to: &Path) -> io::Result<Filed> {
    match fs::symlink_metadata(to) {
        Ok(_) => return Ok(Filed::Held),
        Err(err) if err.kind() == io::ErrorKind::NotFound => {}
        Err(err) => return Err(err),
    }
    fs::rename(from, to)?;
    Ok(Filed::Moved)
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

/// No `renameat2` outside Linux: the checked fallback runs.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn renameat2(_from: &Path, _to: &Path) -> io::Result<()> {
    Err(io::ErrorKind::Unsupported.into())
}

#[cfg(test)]
mod tests {
    use std::fs;

    use crate::offline::blobs::{Filed, checked, rename_noreplace};

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
        assert_eq!(checked(&from, &dir.path().join("x2")).unwrap(), Filed::Held);
        assert_eq!(fs::read(dir.path().join("x2")).unwrap(), b"held");
        assert_eq!(
            checked(&from, &dir.path().join("x3")).unwrap(),
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
        assert_eq!(checked(&from, &to).unwrap(), Filed::Held);
        assert!(fs::symlink_metadata(&to).unwrap().file_type().is_symlink());
    }

    #[test]
    fn a_missing_source_is_an_error() {
        let dir = tempfile::tempdir().unwrap();
        let missing = dir.path().join("gone");
        assert!(rename_noreplace(&missing, &dir.path().join("x")).is_err());
    }
}
