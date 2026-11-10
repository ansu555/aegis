//! EMV verification against independently generated vectors
//! (tools/gen_emv_vectors.py). Parsed with a tiny hand-rolled reader so the
//! crate keeps zero dependencies.

use tapvault_core::emv::{verify_card_challenge, verify_sdad, EmvKeyLayout};
use tapvault_core::CoreError;

const VECTORS: &str = include_str!("emv_vectors.json");

struct V {
    name: String,
    modulus: Vec<u8>,
    exponent: u32,
    un_offset: u8,
    un_len: u8,
    td_len: u8,
    terminal_data: Vec<u8>,
    challenge: [u8; 32],
    sdad: Vec<u8>,
    icc_dyn: Vec<u8>,
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn field<'a>(obj: &'a str, key: &str) -> &'a str {
    let pat = format!("\"{key}\": ");
    let start = obj.find(&pat).unwrap_or_else(|| panic!("missing {key}")) + pat.len();
    let rest = &obj[start..];
    let end = rest.find(|c| c == ',' || c == '\n' || c == '}').unwrap();
    rest[..end].trim().trim_matches('"')
}

fn load() -> Vec<V> {
    VECTORS
        .split('{')
        .skip(1)
        .map(|obj| {
            let mut challenge = [0u8; 32];
            challenge.copy_from_slice(&unhex(field(obj, "challenge")));
            V {
                name: field(obj, "name").to_string(),
                modulus: unhex(field(obj, "modulus")),
                exponent: field(obj, "exponent").parse().unwrap(),
                un_offset: field(obj, "un_offset").parse().unwrap(),
                un_len: field(obj, "un_len").parse().unwrap(),
                td_len: field(obj, "terminal_data_len").parse().unwrap(),
                terminal_data: unhex(field(obj, "terminal_data")),
                challenge,
                sdad: unhex(field(obj, "sdad")),
                icc_dyn: unhex(field(obj, "icc_dynamic_data")),
            }
        })
        .collect()
}

fn layout(v: &V) -> EmvKeyLayout<'_> {
    EmvKeyLayout {
        modulus: &v.modulus,
        exponent: v.exponent,
        un_offset: v.un_offset,
        un_len: v.un_len,
        terminal_data_len: v.td_len,
    }
}

#[test]
fn all_vectors_verify() {
    let vs = load();
    assert_eq!(vs.len(), 6);
    for v in &vs {
        let dyn_data = verify_sdad(&v.modulus, v.exponent, &v.sdad, &v.terminal_data)
            .unwrap_or_else(|e| panic!("{}: {:?}", v.name, e));
        assert_eq!(dyn_data, v.icc_dyn, "{}", v.name);
        verify_card_challenge(&layout(v), &v.sdad, &v.terminal_data, &v.challenge)
            .unwrap_or_else(|e| panic!("{}: {:?}", v.name, e));
    }
}

#[test]
fn wrong_challenge_rejected() {
    for v in &load() {
        let mut ch = v.challenge;
        ch[0] ^= 1;
        assert_eq!(
            verify_card_challenge(&layout(v), &v.sdad, &v.terminal_data, &ch),
            Err(CoreError::EmvChallengeMismatch),
            "{}",
            v.name
        );
        // bytes beyond un_len are not bound by the card; changing them is fine
        let mut ch2 = v.challenge;
        ch2[31] ^= 1;
        assert!(verify_card_challenge(&layout(v), &v.sdad, &v.terminal_data, &ch2).is_ok());
    }
}

#[test]
fn tampered_terminal_data_rejected() {
    for v in &load() {
        // Flip a byte outside the UN window (if any) — hash must catch it.
        let mut td = v.terminal_data.clone();
        let un_end = (v.un_offset as usize) + (v.un_len as usize);
        let idx = if un_end < td.len() { td.len() - 1 } else { 0 };
        td[idx] ^= 0x80;
        assert!(verify_sdad(&v.modulus, v.exponent, &v.sdad, &td).is_err(), "{}", v.name);
    }
}

#[test]
fn tampered_signature_rejected() {
    for v in &load() {
        for pos in [0usize, v.sdad.len() / 2, v.sdad.len() - 1] {
            let mut s = v.sdad.clone();
            s[pos] ^= 0x01;
            let r = verify_sdad(&v.modulus, v.exponent, &s, &v.terminal_data);
            assert!(r.is_err(), "{} pos {}", v.name, pos);
        }
    }
}

#[test]
fn signature_not_reduced_rejected() {
    let v = &load()[0];
    // s + n has the same residue but must be rejected (and won't fit anyway);
    // use s = n itself to hit the explicit check.
    assert_eq!(
        verify_sdad(&v.modulus, v.exponent, &v.modulus, &v.terminal_data),
        Err(CoreError::SignatureNotReduced)
    );
}

#[test]
fn wrong_key_rejected() {
    let vs = load();
    // Signature from vector 0 against vector 1's key: lengths differ -> BadLength,
    // so compare two same-length keys (1984-bit pair).
    let a = &vs[2];
    let b = &vs[3];
    assert_eq!(a.modulus.len(), b.modulus.len());
    assert!(verify_sdad(&b.modulus, b.exponent, &a.sdad, &a.terminal_data).is_err());
}

#[test]
fn layout_validation() {
    let v = &load()[0];
    let mut l = layout(v);
    l.un_len = 3;
    assert_eq!(l.validate(), Err(CoreError::EmvTerminalDataLayout));
    let mut l = layout(v);
    l.un_offset = 1; // 1 + 4 > 4
    assert_eq!(l.validate(), Err(CoreError::EmvTerminalDataLayout));
    let mut l = layout(v);
    l.exponent = 5;
    assert_eq!(l.validate(), Err(CoreError::EmvUnsupportedExponent));
    let mut padded = vec![0u8];
    padded.extend_from_slice(&v.modulus);
    let mut l = layout(v);
    l.modulus = &padded;
    assert_eq!(l.validate(), Err(CoreError::BadLength));
}
