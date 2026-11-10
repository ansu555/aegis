//! Tapvault core: pure verification and policy logic, no NEAR dependencies.
//!
//! Modules:
//! - [`bigint`]: minimal unsigned big integers for RSA public operations.
//! - [`sha1`], [`sha256`]: software hashes (SHA-1 is required by EMV).

pub mod bigint;
pub mod error;
pub mod sha1;
pub mod sha256;

pub use error::CoreError;

/// Hash abstraction so the contract can use the NEAR sha256 host function
/// while native tests use the software implementation.
pub trait Sha256Hasher {
    fn hashv(parts: &[&[u8]]) -> [u8; 32];
}

/// Software SHA-256.
pub struct SoftSha256;

impl Sha256Hasher for SoftSha256 {
    fn hashv(parts: &[&[u8]]) -> [u8; 32] {
        let mut h = sha256::Sha256::new();
        for p in parts {
            h.update(p);
        }
        h.finalize()
    }
}
