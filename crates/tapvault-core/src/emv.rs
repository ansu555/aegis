//! EMV dynamic data authentication (DDA / fDDA / CDA) verification.
//!
//! A contactless EMV card holds an ICC RSA key pair. On INTERNAL AUTHENTICATE
//! (DDA), inside the GPO response (Visa fDDA) or with its GENERATE AC
//! cryptogram (CDA) it returns *Signed Dynamic Application Data* (SDAD).
//! Recovering the SDAD with the ICC public key yields (EMV Book 2, §6.5.2,
//! Table 17):
//!
//! ```text
//! 6A | 05 or 95 | 01 | Ldd | ICC dynamic data (Ldd) | BB..BB pad | SHA-1 (20) | BC
//! ```
//!
//! The Signed Data Format is `05`, except for Visa fDDA on an online (ARQC)
//! transaction, which uses `95`. Both are hashed the same way.
//!
//! where the SHA-1 covers bytes `05 .. pad` followed by the *terminal dynamic
//! data* the reader sent (the DDOL-concatenated values; for the default DDOL
//! `9F37 04` this is just the 4-byte Unpredictable Number).
//!
//! Tapvault puts its challenge into the Unpredictable Number (tag 9F37). The
//! enrolled signer records where the UN sits inside the terminal dynamic data
//! (`un_offset`, `un_len`) and the expected total length, so DDA cards with a
//! non-default DDOL, Visa fDDA (UN || amount || currency || card
//! authentication data 9F69) and CDA (UN only) are handled by the same check.
//! None of this has been run against a real card yet.
//!
//! What we deliberately do NOT do on-chain: verify the issuer / ICC
//! certificate chain. The certificates expose the PAN to anyone who can recover
//! them, so they must never be published. Chain verification happens on the
//! phone at enrolment; only the ICC public key goes on-chain.

use crate::bigint;
use crate::sha1;
use crate::CoreError;

/// ICC modulus is at most 248 bytes (1984 bits) per EMV Book 2.
pub const MAX_ICC_MODULUS_BYTES: usize = 248;
pub const MIN_ICC_MODULUS_BYTES: usize = 64;
pub const MIN_UN_LEN: u8 = 4;
pub const MAX_UN_LEN: u8 = 8;
pub const MAX_TERMINAL_DATA_LEN: u8 = 64;

/// Fixed part of the recovered SDAD around the dynamic data:
/// header(1) + format(1) + hash alg(1) + Ldd(1) + hash(20) + trailer(1).
const SDAD_OVERHEAD: usize = 25;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EmvKeyLayout<'a> {
    pub modulus: &'a [u8],
    pub exponent: u32,
    /// Offset of the 9F37 Unpredictable Number inside the terminal dynamic data.
    pub un_offset: u8,
    /// Length of the Unpredictable Number (4 for almost all cards).
    pub un_len: u8,
    /// Exact length of the terminal dynamic data this card is sent.
    pub terminal_data_len: u8,
}

impl<'a> EmvKeyLayout<'a> {
    /// Structural checks done once at enrolment and again at every use.
    pub fn validate(&self) -> Result<(), CoreError> {
        let n = self.modulus.len();
        if !(MIN_ICC_MODULUS_BYTES..=MAX_ICC_MODULUS_BYTES).contains(&n) {
            return Err(CoreError::EmvModulusTooLarge);
        }
        if self.modulus[0] == 0 {
            // Length must be the true byte length of the modulus.
            return Err(CoreError::BadLength);
        }
        if self.modulus[n - 1] & 1 == 0 {
            // RSA moduli are odd.
            return Err(CoreError::BadLength);
        }
        if self.exponent != 3 && self.exponent != 65537 {
            return Err(CoreError::EmvUnsupportedExponent);
        }
        if self.un_len < MIN_UN_LEN || self.un_len > MAX_UN_LEN {
            return Err(CoreError::EmvTerminalDataLayout);
        }
        if self.terminal_data_len > MAX_TERMINAL_DATA_LEN
            || (self.un_offset as usize) + (self.un_len as usize) > self.terminal_data_len as usize
        {
            return Err(CoreError::EmvTerminalDataLayout);
        }
        Ok(())
    }
}

/// Recover and structurally check an SDAD, then check the SHA-1 against the
/// terminal dynamic data. Returns the ICC dynamic data on success.
pub fn verify_sdad(
    modulus: &[u8],
    exponent: u32,
    sdad: &[u8],
    terminal_data: &[u8],
) -> Result<Vec<u8>, CoreError> {
    let nic = modulus.len();
    if sdad.len() != nic || nic < SDAD_OVERHEAD + 1 {
        return Err(CoreError::BadLength);
    }
    let rec = bigint::rsa_public(modulus, exponent, sdad)?;

    if rec[0] != 0x6A {
        return Err(CoreError::EmvBadHeader);
    }
    if rec[nic - 1] != 0xBC {
        return Err(CoreError::EmvBadTrailer);
    }
    // 05: DDA, CDA and offline fDDA; 95: Visa fDDA on an online (ARQC) transaction.
    if rec[1] != 0x05 && rec[1] != 0x95 {
        return Err(CoreError::EmvBadFormat);
    }
    if rec[2] != 0x01 {
        return Err(CoreError::EmvBadHashAlgorithm);
    }
    let ldd = rec[3] as usize;
    if ldd + SDAD_OVERHEAD > nic {
        return Err(CoreError::EmvBadDynamicDataLength);
    }
    let dyn_start = 4;
    let pad_start = dyn_start + ldd;
    let hash_start = nic - 21;
    if rec[pad_start..hash_start].iter().any(|&b| b != 0xBB) {
        return Err(CoreError::EmvBadPadding);
    }
    let expected = sha1::sha1(&[&rec[1..hash_start], terminal_data]);
    if expected[..] != rec[hash_start..nic - 1] {
        return Err(CoreError::EmvHashMismatch);
    }
    Ok(rec[dyn_start..pad_start].to_vec())
}

/// Full Tapvault check: the card signed terminal data whose Unpredictable
/// Number equals the first `un_len` bytes of `challenge`.
pub fn verify_card_challenge(
    key: &EmvKeyLayout,
    sdad: &[u8],
    terminal_data: &[u8],
    challenge: &[u8; 32],
) -> Result<(), CoreError> {
    key.validate()?;
    if terminal_data.len() != key.terminal_data_len as usize {
        return Err(CoreError::EmvTerminalDataLayout);
    }
    let off = key.un_offset as usize;
    let len = key.un_len as usize;
    if terminal_data[off..off + len] != challenge[..len] {
        return Err(CoreError::EmvChallengeMismatch);
    }
    verify_sdad(key.modulus, key.exponent, sdad, terminal_data)?;
    Ok(())
}
