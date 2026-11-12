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
android/core/           Kotlin client: NEAR transactions, contract calls,
                        EMV card protocol (certificate recovery, DDA),
                        simulated card. Runs on the JVM, tested against
                        testnet.
android/app/            Android app: Keystore phone key behind biometrics,
                        NFC card taps, onboarding, send, approvals, freeze
tools/                  Test-vector, test-card and CA-key generators
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

### Android app and the live testnet test

Needs JDK 17 and the Android SDK (`platforms;android-35`, `build-tools;35.0.0`);
put `sdk.dir=...` in `android/local.properties`. Build the contract wasm first
(the app deploys each new wallet itself).

```bash
cd android
./gradlew :core:test                      # vectors, EMV, P-256 (offline)
TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*TestnetE2E*'   # live testnet
./gradlew :app:assembleDebug              # -> app/build/outputs/apk/debug/app-debug.apk
```

The testnet test runs the app's own client code end to end: it creates fresh
faucet accounts, deploys and initialises a wallet, enrols the (simulated)
card through its certificate chain, locks the account, pays 0.5 NEAR with a
phone signature and a card tap, checks that an over-limit payment waits,
that a replayed card signature is refused, and that a card tap freezes the
wallet.

Install the APK on an Android phone with NFC (`adb install`, or open the APK
file). Setting up a wallet takes about a minute: one fingerprint for the
wallet, one card tap plus a fingerprint to add the card, and one fingerprint
to lock it with your limits. No NFC? Pick "Demo card" on the first screen.

### Measured gas

On the sandbox (limit 300 Tgas per receipt):

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
| NEAR contract | Done, 23 flow tests + reference vectors + sandbox test on the compiled wasm |
| Security review | Independent review: 7 findings, all fixed and re-reviewed (see DESIGN.md §9). Not a professional audit |
| Kotlin client | Done, vectors match the contract byte for byte, live testnet end-to-end test passes |
| Android app | Done: onboarding, send, approvals, freeze, card check, demo card |
| Real-card testing | Next: needs an Android phone with NFC and a DDA-capable card |
| Relayer / merchant POS | Not needed for the phone flow (the app pays its own gas); POS not started |

Contract build: `tapvault_contract.wasm`, SHA-256 (base58)
`5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`. Every wallet's `code_hash` on
chain should equal this; e.g. the end-to-end test wallet
[`tv-e2e-cdbefdd9ff.testnet`](https://testnet.nearblocks.io/address/tv-e2e-cdbefdd9ff.testnet)
runs it and has no access keys.

## Hackathon notes

Built for NEAR HACKATHON (Nov 2026). Tracks: **Real-World Money** (pay with
the card you already carry), **AI Security & Defense** (spending limits and a
policy engine an agent or a person can't bypass), **Open Innovation**.

The protocol design started as a Solana prototype on 9 Nov 2026 (not in this
repo). Everything here is the NEAR implementation, built during the
hackathon window.

## License

MIT
