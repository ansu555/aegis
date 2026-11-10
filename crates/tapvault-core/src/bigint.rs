//! Minimal unsigned big-integer arithmetic for RSA *public* operations only.
//!
//! Limbs are `u32`, little-endian (limb 0 is least significant). `u32` limbs
//! with `u64` intermediates are used on purpose: wasm32 has native 64-bit
//! multiply but emulates 128-bit, so this is the cheap shape on-chain.
//!
//! Nothing here handles secrets, so constant-time behaviour is not required.

use crate::CoreError;

/// Largest supported modulus in bytes (2048-bit). EMV ICC keys are <= 1984 bits.
pub const MAX_MODULUS_BYTES: usize = 256;

pub fn from_be_bytes(b: &[u8]) -> Vec<u32> {
    let n = core::cmp::max(1, (b.len() + 3) / 4);
    let mut out = vec![0u32; n];
    for (i, &byte) in b.iter().rev().enumerate() {
        out[i / 4] |= (byte as u32) << (8 * (i % 4));
    }
    out
}

/// Big-endian encoding left-padded to exactly `len` bytes.
/// Returns `None` if the value does not fit.
pub fn to_be_bytes(limbs: &[u32], len: usize) -> Option<Vec<u8>> {
    let mut out = vec![0u8; len];
    for (i, limb) in limbs.iter().enumerate() {
        for k in 0..4 {
            let byte = ((limb >> (8 * k)) & 0xff) as u8;
            let pos = i * 4 + k; // position from the least-significant end
            if pos < len {
                out[len - 1 - pos] = byte;
            } else if byte != 0 {
                return None;
            }
        }
    }
    Some(out)
}

/// Number of significant limbs (0 for zero).
fn sig_len(a: &[u32]) -> usize {
    let mut n = a.len();
    while n > 0 && a[n - 1] == 0 {
        n -= 1;
    }
    n
}

pub fn is_zero(a: &[u32]) -> bool {
    sig_len(a) == 0
}

pub fn cmp(a: &[u32], b: &[u32]) -> core::cmp::Ordering {
    let (la, lb) = (sig_len(a), sig_len(b));
    if la != lb {
        return la.cmp(&lb);
    }
    for i in (0..la).rev() {
        if a[i] != b[i] {
            return a[i].cmp(&b[i]);
        }
    }
    core::cmp::Ordering::Equal
}

/// Schoolbook multiplication. Output has `a.len() + b.len()` limbs.
pub fn mul(a: &[u32], b: &[u32]) -> Vec<u32> {
    let mut out = vec![0u32; a.len() + b.len()];
    for (i, &ai) in a.iter().enumerate() {
        if ai == 0 {
            continue;
        }
        let mut carry: u64 = 0;
        for (j, &bj) in b.iter().enumerate() {
            let t = (ai as u64) * (bj as u64) + (out[i + j] as u64) + carry;
            out[i + j] = t as u32;
            carry = t >> 32;
        }
        // Propagate the row's final carry; the full product always fits in
        // a.len() + b.len() limbs, so this never runs past the end.
        let mut k = i + b.len();
        while carry != 0 {
            let t = (out[k] as u64) + carry;
            out[k] = t as u32;
            carry = t >> 32;
            k += 1;
        }
    }
    out
}

/// Remainder `u mod v` (Knuth TAOCP vol. 2, Algorithm D; structure follows
/// Hacker's Delight `divmnu`). Result has exactly `v.len()` limbs.
pub fn rem(u: &[u32], v: &[u32]) -> Result<Vec<u32>, CoreError> {
    let n = sig_len(v);
    if n == 0 {
        return Err(CoreError::ModulusZero);
    }
    let m = sig_len(u);
    let mut out = vec![0u32; v.len()];

    if cmp(u, v) == core::cmp::Ordering::Less {
        out[..m].copy_from_slice(&u[..m]);
        return Ok(out);
    }

    if n == 1 {
        let d = v[0] as u64;
        let mut r: u64 = 0;
        for i in (0..m).rev() {
            r = ((r << 32) | u[i] as u64) % d;
        }
        out[0] = r as u32;
        return Ok(out);
    }

    // Normalise so the top limb of the divisor has its high bit set.
    let s = v[n - 1].leading_zeros();
    let shl = |hi: u32, lo: u32| -> u32 {
        if s == 0 {
            hi
        } else {
            (((hi as u64) << s) | ((lo as u64) >> (32 - s))) as u32
        }
    };

    let mut vn = vec![0u32; n];
    for i in (1..n).rev() {
        vn[i] = shl(v[i], v[i - 1]);
    }
    vn[0] = if s == 0 { v[0] } else { v[0] << s };

    let mut un = vec![0u32; m + 1];
    un[m] = if s == 0 { 0 } else { (u[m - 1] as u64 >> (32 - s)) as u32 };
    for i in (1..m).rev() {
        un[i] = shl(u[i], u[i - 1]);
    }
    un[0] = if s == 0 { u[0] } else { u[0] << s };

    const B: u64 = 1 << 32;
    let vtop = vn[n - 1] as u64;
    let vsec = vn[n - 2] as u64;

    for j in (0..=(m - n)).rev() {
        let num = ((un[j + n] as u64) << 32) | un[j + n - 1] as u64;
        let mut qhat = num / vtop;
        let mut rhat = num % vtop;
        loop {
            // Short-circuit keeps qhat < B before the product is formed (no overflow).
            if qhat >= B || qhat * vsec > ((rhat << 32) | un[j + n - 2] as u64) {
                qhat -= 1;
                rhat += vtop;
                if rhat < B {
                    continue;
                }
            }
            break;
        }

        // Multiply and subtract.
        let mut borrow: i64 = 0;
        for i in 0..n {
            let p = qhat * vn[i] as u64;
            let t = un[i + j] as i64 - borrow - (p & 0xffff_ffff) as i64;
            un[i + j] = t as u32;
            borrow = (p >> 32) as i64 - (t >> 32);
        }
        let t = un[j + n] as i64 - borrow;
        un[j + n] = t as u32;

        if t < 0 {
            // qhat was one too large: add the divisor back.
            let mut carry: u64 = 0;
            for i in 0..n {
                let t2 = un[i + j] as u64 + vn[i] as u64 + carry;
                un[i + j] = t2 as u32;
                carry = t2 >> 32;
            }
            un[j + n] = un[j + n].wrapping_add(carry as u32);
        }
    }

    // Denormalise the remainder.
    for i in 0..n {
        out[i] = if s == 0 {
            un[i]
        } else {
            ((un[i] as u64 >> s) | ((un[i + 1] as u64) << (32 - s))) as u32
        };
    }
    Ok(out)
}

pub fn mul_mod(a: &[u32], b: &[u32], n: &[u32]) -> Result<Vec<u32>, CoreError> {
    rem(&mul(a, b), n)
}

/// `base^exp mod n` for a small public exponent. Requires `base < n`.
pub fn pow_mod_u32(base: &[u32], exp: u32, n: &[u32]) -> Result<Vec<u32>, CoreError> {
    if is_zero(n) {
        return Err(CoreError::ModulusZero);
    }
    if exp == 0 {
        let mut one = vec![0u32; n.len()];
        one[0] = 1;
        return rem(&one, n);
    }
    let top = 31 - exp.leading_zeros();
    let mut acc = rem(base, n)?;
    for bit in (0..top).rev() {
        acc = mul_mod(&acc, &acc, n)?;
        if (exp >> bit) & 1 == 1 {
            acc = mul_mod(&acc, base, n)?;
        }
    }
    Ok(acc)
}

/// RSA public operation on big-endian byte strings: returns `sig^e mod n`
/// encoded to the modulus byte length. Rejects `sig >= n`.
pub fn rsa_public(modulus_be: &[u8], exponent: u32, sig_be: &[u8]) -> Result<Vec<u8>, CoreError> {
    if modulus_be.is_empty() || modulus_be.len() > MAX_MODULUS_BYTES || sig_be.len() != modulus_be.len() {
        return Err(CoreError::BadLength);
    }
    let n = from_be_bytes(modulus_be);
    if is_zero(&n) {
        return Err(CoreError::ModulusZero);
    }
    let s = from_be_bytes(sig_be);
    if cmp(&s, &n) != core::cmp::Ordering::Less {
        return Err(CoreError::SignatureNotReduced);
    }
    let r = pow_mod_u32(&s, exponent, &n)?;
    to_be_bytes(&r, modulus_be.len()).ok_or(CoreError::BadLength)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// xorshift64* — deterministic test PRNG.
    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 >> 12;
            self.0 ^= self.0 << 25;
            self.0 ^= self.0 >> 27;
            self.0.wrapping_mul(0x2545F4914F6CDD1D)
        }
        fn limbs(&mut self, n: usize) -> Vec<u32> {
            (0..n).map(|_| self.next() as u32).collect()
        }
    }

    fn add(a: &[u32], b: &[u32]) -> Vec<u32> {
        let len = core::cmp::max(a.len(), b.len()) + 1;
        let mut out = vec![0u32; len];
        let mut carry = 0u64;
        for i in 0..len {
            let t = *a.get(i).unwrap_or(&0) as u64 + *b.get(i).unwrap_or(&0) as u64 + carry;
            out[i] = t as u32;
            carry = t >> 32;
        }
        out
    }

    /// Independent check: r = u mod v iff r < v and v | (u - r). We verify by
    /// computing q via repeated structure: u - r must equal q*v for the q we
    /// get from a slow shift-subtract division.
    fn slow_divmod(u: &[u32], v: &[u32]) -> (Vec<u32>, Vec<u32>) {
        // bitwise long division
        let bits = u.len() * 32;
        let mut q = vec![0u32; u.len()];
        let mut r = vec![0u32; v.len() + 1];
        for i in (0..bits).rev() {
            // r <<= 1
            let mut carry = 0u32;
            for limb in r.iter_mut() {
                let nc = *limb >> 31;
                *limb = (*limb << 1) | carry;
                carry = nc;
            }
            r[0] |= (u[i / 32] >> (i % 32)) & 1;
            if cmp(&r, v) != core::cmp::Ordering::Less {
                // r -= v
                let mut borrow = 0i64;
                for k in 0..r.len() {
                    let t = r[k] as i64 - *v.get(k).unwrap_or(&0) as i64 - borrow;
                    r[k] = t as u32;
                    borrow = if t < 0 { 1 } else { 0 };
                }
                q[i / 32] |= 1 << (i % 32);
            }
        }
        (q, r)
    }

    #[test]
    fn rem_matches_slow_division_random() {
        let mut rng = Rng(0x9E3779B97F4A7C15);
        for round in 0..400 {
            let vn = 1 + (rng.next() as usize % 20);
            let un = vn + (rng.next() as usize % 25);
            let mut v = rng.limbs(vn);
            // exercise various top-limb magnitudes, including tiny ones
            match round % 4 {
                0 => v[vn - 1] |= 0x8000_0000,
                1 => v[vn - 1] = (v[vn - 1] & 0xff) | 1,
                2 => v[vn - 1] = 1,
                _ => v[vn - 1] |= 1,
            }
            let u = rng.limbs(un);
            let r = rem(&u, &v).unwrap();
            let (q, r2) = slow_divmod(&u, &v);
            assert_eq!(cmp(&r, &r2), core::cmp::Ordering::Equal, "round {round}");
            // and q*v + r == u
            let recon = add(&mul(&q, &v), &r);
            assert_eq!(cmp(&recon, &u), core::cmp::Ordering::Equal, "recon round {round}");
        }
    }

    #[test]
    fn rem_edge_cases() {
        // divisor that triggers the add-back step: classic Knuth test values
        let u = [0x0000_0000, 0x0000_0000, 0x8000_0000, 0x7fff_ffff];
        let v = [0x0000_0001, 0x0000_0000, 0x8000_0000];
        let r = rem(&u, &v).unwrap();
        let (_, r2) = slow_divmod(&u, &v);
        assert_eq!(cmp(&r, &r2), core::cmp::Ordering::Equal);

        let u = [0xffff_ffff, 0xffff_ffff, 0xffff_ffff, 0xffff_ffff];
        let v = [0xffff_ffff, 0xffff_ffff];
        let r = rem(&u, &v).unwrap();
        assert!(is_zero(&r));

        assert_eq!(rem(&[5], &[0]), Err(CoreError::ModulusZero));
        assert_eq!(rem(&[3], &[7]).unwrap(), vec![3]);
    }

    #[test]
    fn byte_roundtrip() {
        let b = [0x01u8, 0x02, 0x03, 0x04, 0x05];
        let l = from_be_bytes(&b);
        assert_eq!(to_be_bytes(&l, 5).unwrap(), b.to_vec());
        assert_eq!(to_be_bytes(&l, 7).unwrap(), vec![0, 0, 1, 2, 3, 4, 5]);
        assert!(to_be_bytes(&l, 4).is_none());
    }

    #[test]
    fn small_rsa() {
        // n = 3233 (61*53), e = 17, d = 2753; signature on m = 65 is 65^d = 588
        let n = 3233u32.to_be_bytes();
        let c = 588u32.to_be_bytes();
        let m = rsa_public(&n, 17, &c).unwrap();
        assert_eq!(u32::from_be_bytes(m.try_into().unwrap()), 65);
        // sig >= n rejected
        assert_eq!(rsa_public(&n, 17, &n), Err(CoreError::SignatureNotReduced));
    }
}
