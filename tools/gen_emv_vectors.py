#!/usr/bin/env python3
"""Generate synthetic EMV DDA/fDDA test vectors for tapvault-core.

Each vector is an RSA "card" key (as EMV ICC keys are: e=3 or 65537, modulus
up to 248 bytes) and a Signed Dynamic Application Data produced exactly as a
card would (EMV Book 2 §6.5.2), over terminal dynamic data whose
Unpredictable Number is the first `un_len` bytes of a given challenge.

These are NOT real-card vectors. Real-card testing must be done on hardware.

Usage: python3 tools/gen_emv_vectors.py > crates/tapvault-core/tests/emv_vectors.json
"""
import hashlib
import json
import os
import random

from cryptography.hazmat.primitives.asymmetric import rsa

rng = random.Random(0x7A9)


def rb(n):
    return bytes(rng.getrandbits(8) for _ in range(n))


def make_sdad(n, d, nbytes, icc_dyn, terminal_data):
    ldd = len(icc_dyn)
    pad = nbytes - ldd - 25
    assert pad >= 0
    body = bytes([0x05, 0x01, ldd]) + icc_dyn + b"\xBB" * pad
    h = hashlib.sha1(body + terminal_data).digest()
    rec = b"\x6A" + body + h + b"\xBC"
    assert len(rec) == nbytes
    m = int.from_bytes(rec, "big")
    assert m < n
    s = pow(m, d, n)
    return s.to_bytes(nbytes, "big")


def vector(name, key_bits, e, layout):
    while True:
        k = rsa.generate_private_key(public_exponent=e, key_size=key_bits)
        nums = k.private_numbers()
        n, d = nums.public_numbers.n, nums.d
        nbytes = (n.bit_length() + 7) // 8
        # EMV recovered data starts with 0x6A, so the modulus must exceed it at the top byte.
        if n.to_bytes(nbytes, "big")[0] > 0x6A:
            break
    challenge = rb(32)
    un_offset, un_len, td_len = layout
    td = bytearray(rb(td_len))
    td[un_offset:un_offset + un_len] = challenge[:un_len]
    td = bytes(td)
    idn = rb(8)
    icc_dyn = bytes([len(idn)]) + idn
    sdad = make_sdad(n, d, nbytes, icc_dyn, td)
    return {
        "name": name,
        "modulus": n.to_bytes(nbytes, "big").hex(),
        "exponent": e,
        "un_offset": un_offset,
        "un_len": un_len,
        "terminal_data_len": td_len,
        "terminal_data": td.hex(),
        "challenge": challenge.hex(),
        "sdad": sdad.hex(),
        "icc_dynamic_data": icc_dyn.hex(),
    }


def main():
    vs = [
        vector("dda_1024_e3_default_ddol", 1024, 3, (0, 4, 4)),
        vector("dda_1408_e3_default_ddol", 1408, 3, (0, 4, 4)),
        vector("dda_1984_e3_default_ddol", 1984, 3, (0, 4, 4)),
        vector("dda_1984_e3_un8_offset2", 1984, 3, (2, 8, 14)),
        vector("fdda_like_1152_e3", 1152, 3, (0, 4, 16)),
        vector("dda_1152_e65537", 1152, 65537, (0, 4, 4)),
    ]
    print(json.dumps(vs, indent=1))


if __name__ == "__main__":
    main()
