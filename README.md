# Tapvault

A self-custodial NEAR wallet co-signed by **the bank card you already
carry** and **your phone's biometric key**. No seed phrase, no hardware
wallet to buy.

- Your **contactless debit/credit card** has a chip with an RSA key that can't
  be copied out. Tapvault uses its standard EMV signature (the one terminals
  use to check the card is genuine) as a signer. No PIN, no payment.
- Your **phone** holds a P-256 key in secure hardware, unlocked by your
  fingerprint. Passkeys / FIDO2 security keys work too.
- A NEAR contract verifies both **on-chain** and enforces spending limits,
  delays, freeze and recovery.

Read [`docs/DESIGN.md`](docs/DESIGN.md) for the threat model, including what
this design does **not** protect against.

## How it works

```
 phone (fingerprint)             NEAR: wallet account + Tapvault contract
 ───────────────────             ─────────────────────────────────────────
 sign intent hash  ──propose──▶  store action, schedule seal_challenge
                                 ...next block: seed = random_seed  (sealed)
 read card challenge ◀─────────  H(card-approve ‖ intent ‖ seed)
 tap card (NFC)
 card signs challenge ─approve_card + execute─▶  RSA check, policy, transfer
```

A card signs anything a reader sends it, so a challenge must be impossible
to know before the action is fixed. Tapvault commits the action first and
derives the card's challenge from block randomness captured in a **later
block**. A signature skimmed from your pocket can't match it.

Rules the contract enforces:

| | |
|---|---|
| Every transfer | `threshold` approvals, at least one from a phone / passkey |
| Within daily limits | executes instantly |
| Over the limits | waits a delay (default 24 h); any signer can cancel or freeze |
| Config changes | always delayed; longer if no phone approved (lost-phone recovery) |
| Freeze | any single signer, phone or card tap |
| After setup | the account's own key is deleted; only the contract moves funds |

## Layout

```
crates/tapvault-core/   Dependency-free security logic (native-testable)
  bigint.rs             RSA public-key math (u32 limbs)
  emv.rs                EMV DDA / fDDA signature verification
  webauthn.rs           FIDO2 / passkey assertion checks
  domain.rs             Every signed message, domain-separated
  policy.rs             Limits, delays, approval rules, rate limits
  testcard.rs           Simulated EMV card (tests and demos only)
contract/               NEAR contract (near-sdk)
  src/proposals.rs      propose, seal_challenge, approvals
  src/execute.rs        transfers (NEAR, NEP-141) and config changes
  src/guard.rs          cancel, freeze, close_stale
  tests/flows.rs        end-to-end flows in the mocked runtime
  tests/sandbox.rs      the compiled wasm on a local NEAR node, with gas report
tools/                  Test-vector and test-card generators
docs/DESIGN.md          Security model
```

## Build and test

Requires Rust (the pinned 1.86.0 toolchain installs automatically from
`rust-toolchain.toml`) and, to build the wasm, `cargo-near` 0.17:

```bash
cargo install --locked cargo-near@0.17.0
```

```bash
# all native tests: core crypto + policy, and contract flows (~1 s)
cargo test

# build the contract
cd contract && cargo near build non-reproducible-wasm
# -> target/near/tapvault_contract/tapvault_contract.wasm

# run the wasm on a local NEAR node and print gas per step
# (downloads a nearcore sandbox binary on first run)
cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture
```

Measured on the sandbox (limit 300 Tgas per receipt):

| Step | Gas |
|---|---|
| Phone signature check (P-256 in WASM) | ~32 Tgas |
| Card approval, typical 1024-bit card | 2.6 Tgas |
| Card approval, worst case 1984-bit e = 65537 | 21.3 Tgas |
| Execute a NEAR transfer | 2.7 Tgas |

## Status

| Part | State |
|---|---|
| Core crate | Done, 26 tests |
| NEAR contract | Done, 15 flow tests + sandbox test on the compiled wasm |
| Android app (NFC card tap, Keystore key) | In progress |
| Relayer | Not started |
| Real-card testing | Not started |
| Audit | Not started |

## Hackathon notes

Built for NEAR HACKATHON (Nov 2026). Tracks: **Real-World Money** (pay with
the card you already carry), **AI Security & Defense** (spending limits and a
policy engine an agent or a person can't bypass), **Open Innovation**.

The protocol design started as a Solana prototype on 9 Nov 2026 (not in this
repo). Everything here is the NEAR implementation, built during the
hackathon window.

## License

MIT
