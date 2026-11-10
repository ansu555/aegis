//! WebAuthn assertion checks for FIDO2 security keys and passkeys.
//!
//! An authenticator signs `authenticatorData || SHA-256(clientDataJSON)` with
//! ECDSA-P256-SHA256, so the message the contract verifies for a WebAuthn
//! signer is exactly `authenticatorData || SHA-256(clientDataJSON)`.
//!
//! We check (same approach as widely deployed on-chain WebAuthn verifiers):
//! - rpIdHash (first 32 bytes of authenticatorData) matches the enrolled one,
//! - User Present flag is set, and User Verified if the signer requires it,
//! - `"type":"webauthn.get"` appears at `type_index` in clientDataJSON,
//! - `"challenge":"<base64url(challenge)>"` appears at `challenge_index`.
//!
//! clientDataJSON is produced by the platform/authenticator and covered by the
//! signature, so the indices only tell us where to look; they cannot be used
//! to substitute a different challenge.

use crate::{CoreError, Sha256Hasher};

const FLAG_UP: u8 = 0x01;
const FLAG_UV: u8 = 0x04;
const TYPE_GET: &[u8] = br#""type":"webauthn.get""#;

#[derive(Debug, Clone)]
pub struct Assertion<'a> {
    pub authenticator_data: &'a [u8],
    pub client_data_json: &'a [u8],
    pub challenge_index: u16,
    pub type_index: u16,
}

pub fn base64url_nopad(input: &[u8]) -> Vec<u8> {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = Vec::with_capacity((input.len() * 4 + 2) / 3);
    for chunk in input.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(A[(n >> 18) as usize & 63]);
        out.push(A[(n >> 12) as usize & 63]);
        if chunk.len() > 1 {
            out.push(A[(n >> 6) as usize & 63]);
        }
        if chunk.len() > 2 {
            out.push(A[n as usize & 63]);
        }
    }
    out
}

fn at(hay: &[u8], idx: usize, needle: &[u8]) -> bool {
    hay.get(idx..idx + needle.len()) == Some(needle)
}

/// Validate the assertion and return the message the P-256 signature must
/// cover for this signer.
pub fn signed_message<H: Sha256Hasher>(
    a: &Assertion,
    expected_challenge: &[u8; 32],
    rp_id_hash: &[u8; 32],
    require_uv: bool,
) -> Result<Vec<u8>, CoreError> {
    let ad = a.authenticator_data;
    if ad.len() < 37 {
        return Err(CoreError::WebAuthnAuthDataTooShort);
    }
    if &ad[..32] != rp_id_hash {
        return Err(CoreError::WebAuthnRpIdMismatch);
    }
    let flags = ad[32];
    if flags & FLAG_UP == 0 {
        return Err(CoreError::WebAuthnUserNotPresent);
    }
    if require_uv && flags & FLAG_UV == 0 {
        return Err(CoreError::WebAuthnUserNotVerified);
    }
    let cdj = a.client_data_json;
    if !at(cdj, a.type_index as usize, TYPE_GET) {
        return Err(CoreError::WebAuthnTypeMismatch);
    }
    let mut want = br#""challenge":""#.to_vec();
    want.extend_from_slice(&base64url_nopad(expected_challenge));
    want.push(b'"');
    if !at(cdj, a.challenge_index as usize, &want) {
        return Err(CoreError::WebAuthnChallengeMismatch);
    }
    let cd_hash = H::hashv(&[cdj]);
    let mut msg = Vec::with_capacity(ad.len() + 32);
    msg.extend_from_slice(ad);
    msg.extend_from_slice(&cd_hash);
    Ok(msg)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::SoftSha256;

    #[test]
    fn b64url() {
        assert_eq!(base64url_nopad(b""), b"");
        assert_eq!(base64url_nopad(b"f"), b"Zg");
        assert_eq!(base64url_nopad(b"fo"), b"Zm8");
        assert_eq!(base64url_nopad(b"foo"), b"Zm9v");
        assert_eq!(base64url_nopad(&[0xfb, 0xff]), b"-_8");
    }

    fn sample(challenge: &[u8; 32], flags: u8, rp: &[u8; 32]) -> (Vec<u8>, Vec<u8>, u16, u16) {
        let mut ad = rp.to_vec();
        ad.push(flags);
        ad.extend_from_slice(&[0, 0, 0, 7]);
        let c = String::from_utf8(base64url_nopad(challenge)).unwrap();
        let cdj = format!(r#"{{"type":"webauthn.get","challenge":"{c}","origin":"https://tapvault.app","crossOrigin":false}}"#);
        let type_index = cdj.find(r#""type""#).unwrap() as u16;
        let challenge_index = cdj.find(r#""challenge""#).unwrap() as u16;
        (ad, cdj.into_bytes(), challenge_index, type_index)
    }

    #[test]
    fn accepts_valid_and_rejects_tampering() {
        let ch = [0xABu8; 32];
        let rp = [0x11u8; 32];
        let (ad, cdj, ci, ti) = sample(&ch, FLAG_UP | FLAG_UV, &rp);
        let a = Assertion { authenticator_data: &ad, client_data_json: &cdj, challenge_index: ci, type_index: ti };
        let msg = signed_message::<SoftSha256>(&a, &ch, &rp, true).unwrap();
        assert_eq!(&msg[..ad.len()], &ad[..]);
        assert_eq!(msg.len(), ad.len() + 32);

        // wrong challenge
        assert_eq!(
            signed_message::<SoftSha256>(&a, &[0u8; 32], &rp, true),
            Err(CoreError::WebAuthnChallengeMismatch)
        );
        // wrong rp
        assert_eq!(
            signed_message::<SoftSha256>(&a, &ch, &[0u8; 32], true),
            Err(CoreError::WebAuthnRpIdMismatch)
        );
        // wrong indices
        let bad = Assertion { challenge_index: ci + 1, ..a.clone() };
        assert_eq!(signed_message::<SoftSha256>(&bad, &ch, &rp, true), Err(CoreError::WebAuthnChallengeMismatch));
        let bad = Assertion { type_index: ti + 1, ..a.clone() };
        assert_eq!(signed_message::<SoftSha256>(&bad, &ch, &rp, true), Err(CoreError::WebAuthnTypeMismatch));
    }

    #[test]
    fn flags() {
        let ch = [1u8; 32];
        let rp = [2u8; 32];
        let (ad, cdj, ci, ti) = sample(&ch, FLAG_UP, &rp);
        let a = Assertion { authenticator_data: &ad, client_data_json: &cdj, challenge_index: ci, type_index: ti };
        assert_eq!(signed_message::<SoftSha256>(&a, &ch, &rp, true), Err(CoreError::WebAuthnUserNotVerified));
        assert!(signed_message::<SoftSha256>(&a, &ch, &rp, false).is_ok());
        let (ad, cdj, ci, ti) = sample(&ch, 0, &rp);
        let a = Assertion { authenticator_data: &ad, client_data_json: &cdj, challenge_index: ci, type_index: ti };
        assert_eq!(signed_message::<SoftSha256>(&a, &ch, &rp, false), Err(CoreError::WebAuthnUserNotPresent));
    }

    #[test]
    fn create_type_rejected() {
        let ch = [1u8; 32];
        let rp = [2u8; 32];
        let mut ad = rp.to_vec();
        ad.push(FLAG_UP | FLAG_UV);
        ad.extend_from_slice(&[0, 0, 0, 1]);
        let c = String::from_utf8(base64url_nopad(&ch)).unwrap();
        let cdj = format!(r#"{{"type":"webauthn.create","challenge":"{c}"}}"#);
        let a = Assertion {
            authenticator_data: &ad,
            client_data_json: cdj.as_bytes(),
            challenge_index: cdj.find(r#""challenge""#).unwrap() as u16,
            type_index: 1,
        };
        assert_eq!(signed_message::<SoftSha256>(&a, &ch, &rp, true), Err(CoreError::WebAuthnTypeMismatch));
    }
}
