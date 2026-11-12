# Testing

Last updated 12 Nov 2026. Covers the automated tests (Rust, Kotlin, live
testnet) and the step-by-step plan for the first test on a real Android phone
with real bank cards.

> **Status.** Every automated test passes (12 Nov), including a live testnet
> run of the whole wallet with each of the three simulated card kinds. The
> `EmvTest` case for truncated data object lists was added after those runs.
> **Nothing has been run against a real bank card or a real phone's NFC,
> Keystore or fingerprint prompt yet.** The app itself has no simulated or
> demo card: it only works with a real card. Results and evidence:
> [STATUS.md](STATUS.md). Errors and what they mean:
> [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

Contents:

1. [What is tested how](#what-is-tested-how)
2. [Automated tests](#automated-tests)
3. [Real-phone test plan](#real-phone-test-plan)
4. [Acceptance checklist](#acceptance-checklist)
5. [Test report template](#test-report-template)

## What is tested how

Three levels of evidence are used throughout these docs:

- **Tested:** run against the real thing (real contract code, real testnet).
- **Tested with the simulated card (tests only):** the code ran end to end,
  but the bank card was `SimCard` (Kotlin) or `TestCard` (Rust), and the
  phone key was a software key.
- **Untested:** never run.

| Area | How | Level |
|---|---|---|
| Core crypto and policy (RSA/SDAD checks, limits, delays, messages) | `cargo test`, `crates/tapvault-core` | Tested (native) |
| Contract logic | `contract/tests/flows.rs`, mocked NEAR runtime | Tested with the simulated card |
| Compiled contract and gas | `contract/tests/sandbox.rs`, local nearcore 2.6.5 | Tested with the simulated card |
| Kotlin signs the same bytes as Rust | `VectorsTest.kt` + `contract/tests/vectors.rs` | Tested |
| Card protocol code (APDUs, certificate chain, DDA / fDDA / CDA) | `EmvTest.kt` against `SimCard` | Tested with the simulated card |
| RPC error handling | `RpcProbeTest.kt`, live testnet | Tested |
| Whole wallet on testnet (create, deploy, enrol, lock, pay, wait, cancel, replay, freeze) | `TestnetE2ETest.kt`, live testnet, shipped contract | Tested with the simulated card and a software phone key |
| Android app screens | Emulator (no NFC): 11 Nov with the old demo card; 12 Nov build launches and refuses setup cleanly without NFC | Partly tested; no card flow since the demo card was removed |
| Real phone: NFC antenna, Keystore key, fingerprint prompt | [Real-phone test plan](#real-phone-test-plan) | **Untested** |
| Real Visa, Mastercard, RuPay cards | Card check + the plan below | **Untested** |
| Android 9/10 fingerprint-only path | The plan, if the phone runs Android 9/10 | **Untested** |

## Automated tests

### Suites

| Suite | File(s) | Covers | Count (12 Nov) | Network |
|---|---|---|---|---|
| Core unit tests | `crates/tapvault-core/src/*.rs` (`bigint` 4, `domain` 3, `policy` 4, `sha1` 2, `sha256` 1, `testcard` 2, `webauthn` 4) | Big-integer RSA maths, domain tags, limits/delays/rate limit, hashes, `TestCard` formats 05 and 95, WebAuthn parsing | 20 | No |
| Core EMV vectors | `crates/tapvault-core/tests/emv.rs` + `emv_vectors.json` | SDAD verification against vectors generated independently by `tools/gen_emv_vectors.py`; wrong challenge, tampered data/signature, unreduced signature, wrong key, layout validation | 7 | No |
| Contract flows | `contract/tests/flows.rs` | The wallet's rules in the mocked runtime (list below), including a wallet whose card signs like Visa online fDDA (format 95, 19 bytes of terminal data) | 24 | No |
| Reference vectors | `contract/tests/vectors.rs` | Writes/compares `android/core/src/test/resources/vectors.json` | 1 | No |
| Sandbox | `contract/tests/sandbox.rs` (`#[ignore]`) | Compiled wasm on a local node: outsider can't initialise, setup with a 1024-bit and a 1984-bit card, key deleted, two payments, freeze by card, gas per receipt | 1 (ignored by default) | First run downloads nearcore |
| `EmvTest` | `android/core/src/test/kotlin/app/tapvault/core/EmvTest.kt` | CA key file, TLV, enrolment through the certificate chain, chain refused without the test CA key, wrong card, Card check report, every `SimCard` profile, fDDA full terminal data and format 95, format byte check, Mastercard-style zero-amount fallback, truncated data object lists, recipe encoding, reports never carry the PAN, P-256 DER, Base58 | 15 | No |
| `VectorsTest` | `.../VectorsTest.kt` | Kotlin Borsh/JSON/messages equal the Rust bytes in `vectors.json` | 3 | No |
| `RpcProbeTest` | `.../RpcProbeTest.kt` | How `NearClient` reports a view on an account without code (`CodeDoesNotExist`) and on a missing account (`UNKNOWN_ACCOUNT`) | 1 | Live testnet, only with `TAPVAULT_TESTNET=1` |
| `TestnetE2ETest` | `.../TestnetE2ETest.kt` | The whole wallet on testnet with the app's own client code ([below](#live-testnet-test)) | 1 | Live testnet, only with `TAPVAULT_TESTNET=1` |

The 24 flow tests: setup gives a locked two-signer wallet; setup rejects a card
without proof of possession; phone + card sends instantly within limits; a
Visa online fDDA wallet works end to end (and a UN-only signature doesn't fit
its layout); a card can't approve before the seal; a skimmed signature can't
approve another proposal; over-limit transfers wait; the daily allowance
refills; a failed transfer gives the allowance back; re-adding an enrolled key
is refused; a card's proposal is not an approval; freeze blocks transfers and
freeze signatures are single-use; config changes void other proposals; a card
can't veto its own removal but the phone can veto anything; forged or
misdirected phone signatures are rejected; per-signer rate limit; open
proposals capped per signer; the card approval window closes; delays run from
full approval; unapproved proposals can be cleaned up after a day; only the
wallet account can initialise; finalize must come from the wallet account;
freeze is refused during setup; passkeys must require user verification.

The Android app module (`android/app`) has no automated tests.

### Rust

Requirements: the pinned toolchain (rustc 1.86.0 with the `wasm32-unknown-unknown`
target, installed automatically from `rust-toolchain.toml`). Newer toolchain
combinations broke the build earlier; don't change the pins.

```bash
# from the repo root: everything native (core + contract); no network needed
# once the crates are downloaded
cargo test
```

Expected (one `test result` line per test binary):

| Binary | Result |
|---|---|
| `tapvault_core` (unit) | 20 passed |
| `tests/emv.rs` | 7 passed |
| `tapvault_contract` (unit) | 0 tests |
| `tests/flows.rs` | 24 passed |
| `tests/sandbox.rs` | 0 passed, 1 ignored |
| `tests/vectors.rs` | 1 passed |
| Doc-tests `tapvault_core`, `tapvault_contract` | 0 tests each |

Narrower runs:

```bash
cargo test -p tapvault-core                      # 20 + 7
cargo test -p tapvault-contract --test flows     # 24
cargo test -p tapvault-contract --test flows freeze   # name filter
```

### Contract on a local node (sandbox)

```bash
cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture
```

- Downloads a nearcore sandbox binary on first run, pinned to 2.6.5
  (`sandbox_with_version("2.6.5")`; newer nodes changed an RPC field that
  near-workspaces 0.20 expects).
- Compiles the contract from source at test time
  (`near_workspaces::compile_project`). It does **not** read
  `contract/res/tapvault_contract.wasm`; the gas figures are for the current
  source.
- Prints the gas of every receipt the wallet account ran and ends with
  `most expensive single receipt: … Tgas (limit 300)`; the test fails if any
  receipt reaches 300 Tgas.

Measured on 12 Nov:

| Step | Gas |
|---|---|
| deploy + `new` | 56.4 Tgas |
| `setup_add_signer`, 1024-bit card | 34.7 Tgas |
| `setup_add_signer`, 1984-bit card (e = 65537) | 53.4 Tgas |
| `setup_finalize` | 35.1 Tgas |
| `propose` (+ `seal_challenge`) | 34.8 + 2.0 Tgas (the P-256 check is about 32 of it) |
| `approve_card`, 1024-bit | 2.7 Tgas |
| `approve_card`, 1984-bit, e = 65537 | 21.3 Tgas |
| `execute`, NEAR transfer (+ `on_transfer` callback) | 2.7 + 1.6 Tgas |
| `freeze` by card | 2.4 Tgas |

### Kotlin (JVM)

Requirements: JDK 17 (`android/core` uses `jvmToolchain(17)`; Gradle 8.11.1
failed on newer JDKs here) and the Android SDK location in
`android/local.properties` (`sdk.dir`), because Gradle configures the app
module too. Build setup details: [ANDROID.md](ANDROID.md).

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS; elsewhere point JAVA_HOME at a JDK 17
./gradlew :core:test                               # all 20 Kotlin tests
./gradlew :core:test --tests '*EmvTest*'           # one class
```

`/usr/libexec/java_home -v 17` can return a newer JDK when no JDK 17 is
installed; `"$JAVA_HOME/bin/java" -version` must report `17.x`
(`android/build-apk.sh` does this check itself).

Expected: 20 tests, all `PASSED` (`testLogging` prints one line per test).
**`RpcProbeTest` and `TestnetE2ETest` also show `PASSED` without
`TAPVAULT_TESTNET=1`, but they returned immediately and tested nothing.**
`TestnetE2ETest` prints
`skipped: set TAPVAULT_TESTNET=1 to run against testnet` in that case.

Gradle skips a test task whose inputs didn't change (`UP-TO-DATE`).
Environment variables are not inputs (except `TAPVAULT_WASM`, below), so add
`--rerun` whenever you re-run with different variables (for example another
card profile).

### Live testnet test

`TestnetE2ETest.walletLifecycleOnTestnet` runs the app's own client code
(`Onboarding`, `TapvaultWallet`, `CardService`, `NearClient`) against NEAR
testnet. Stand-ins: `SoftP256Signer` (a software P-256 key) for the phone's
Keystore key, and `SimCard` speaking real APDUs for the NFC card.

The test deploys the contract file named by `TAPVAULT_WASM`.
`android/core/build.gradle.kts` passes your shell's `TAPVAULT_WASM` through
when it is set, and otherwise points it at the shipped build,
`<repo>/contract/res/tapvault_contract.wasm`, the file the app deploys. A
fresh clone needs no preparation. To confirm which build that is:

```bash
# from the repo root
shasum -a 256 contract/res/tapvault_contract.wasm
# 3ea83cc88fecda3c727d624e5a935ed1fe20cc5623cafafa4e86969bf7cdc2ec
```

To test a local build instead, set `TAPVAULT_WASM` to its absolute path.
`cargo near build non-reproducible-wasm` writes
`<repo>/target/near/tapvault_contract/tapvault_contract.wasm`, which has a
different hash (the build isn't reproducible across machines; see
[`contract/res/README.md`](../contract/res/README.md)). The value of
`TAPVAULT_WASM` is a Gradle input, so changing it reruns the test task.

Run it once per card kind, one after the other:

```bash
cd android
TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*RpcProbe*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=DDA            ./gradlew :core:test --tests '*TestnetE2E*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=VISA_FDDA      ./gradlew :core:test --tests '*TestnetE2E*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=MASTERCARD_CDA ./gradlew :core:test --tests '*TestnetE2E*' --rerun
```

Environment variables:

| Variable | Needed | Meaning |
|---|---|---|
| `TAPVAULT_TESTNET` | Yes, `1` | Without it both live tests return at once |
| `TAPVAULT_WASM` | No | The contract file to deploy, as an absolute path; default `<repo>/contract/res/tapvault_contract.wasm` (see above) |
| `TAPVAULT_CARD_PROFILE` | No (default `DDA`) | `DDA`, `VISA_FDDA` or `MASTERCARD_CDA` ([profiles](#simulated-cards)) |
| `TAPVAULT_SPONSOR_ACCOUNT`, `TAPVAULT_SPONSOR_KEY` | No; set both | A funded testnet account and its private key in NEAR CLI format (`ed25519:…`, the `private_key` field of its file under `~/.near-credentials/testnet/`). Used when the faucet refuses. The account without the key is ignored (no sponsor) |
| `TAPVAULT_FORCE_SPONSOR` | No | `1` skips the faucet and uses the sponsor at once (only with a sponsor) |

What one run does, in order (each line is printed with an `[e2e]` prefix):

1. Creates `tv-e2e-<10 hex>.testnet` and `tv-e2e-<10 hex>-gas.testnet` from
   the faucet (or sub-accounts of the sponsor).
2. Deploys the wasm and calls `new` in one transaction (the software phone
   key signs the create message); prints the explorer link.
3. Enrols the simulated card in one session: reads its certificate chain
   (the test adds `SimCard.TEST_CA` to the CA list) and signs the enrolment
   challenge; prints the card report and
   `card signs with <DDA|FDDA|CDA>; terminal data <n> bytes, UN at 0`; then
   adds the card with `setup_add_signer`. (The test deploys before it reads
   the card; the app reads the card first.)
4. Finalizes with threshold 2, limits 1 NEAR at once / 2 NEAR per day and
   `Delays.SHORTEST` (1 h, 1 h, 24 h), like the app; asserts the wallet
   account has no access keys.
5. Pays 0.5 NEAR to a fresh 64-hex implicit account (phone proposes, card
   approves and executes in one transaction) and asserts the receiver holds
   exactly 0.5 NEAR.
6. Proposes 1.5 NEAR (over the limit), card approves, asserts `execute` fails
   with `Delay has not elapsed`, then the phone cancels it.
7. Asserts a card signature for one proposal can't approve the next
   (`Card signature verification failed`).
8. Freezes with a card tap and asserts a new transfer is refused.

Expected per profile: `DDA` signs with 4 bytes of terminal data, format 05;
`VISA_FDDA` with 19 bytes, format 95; `MASTERCARD_CDA` with 4 bytes, format 05,
after a declined zero-amount attempt (`AAC`) and a retry with amount 100.

Costs: two faucet accounts per run (the faucet allows about 10 accounts per IP
per 12 h, then HTTP 429), or 6 NEAR from the sponsor (5.5 wallet + 0.5 fee
account). The frozen test wallet stays on testnet with its remaining NEAR.

12 Nov results (wallets have no access keys; code hash
`5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`):

| Profile | Wallet | Payment |
|---|---|---|
| `VISA_FDDA` | `tv-e2e-cc807a553f.testnet` | `4NmY7XUN45wC6NBUfSnTAE3LD5LbBUA8LeQ3hXh2uRjS` |
| `MASTERCARD_CDA` | `tv-e2e-104d487bcc.testnet` | `ENyQDKGoR9FWYZwLtxRG6vNbypsm22vFqLugpEN3U6t1` |
| `DDA` | `tv-e2e-0b6a860ed9.testnet` | `4rRkvPbpXFPqNpWiH3r3nM8fYDpiFnnk3cnnfJhhhdjY` |

`RpcProbeTest` checks `tapvault-skyyy.testnet` (an existing account with no
code) and `tv-nonexistent-zz9q.testnet` (missing). It fails if either fact
changes.

### Reference vectors

The phone signs bytes the contract recomputes, so Kotlin's Borsh, JSON and
message hashes must equal Rust's byte for byte.

```bash
# after changing a contract type or a signed message
UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors   # rewrites android/core/src/test/resources/vectors.json
cargo test -p tapvault-contract --test vectors                    # fails if the file is stale
cd android && ./gradlew :core:test --tests '*VectorsTest*'        # Kotlin must match
```

Fix `Model.kt` / `Domain.kt` until `VectorsTest` passes; never edit
`vectors.json` by hand.

### Simulated cards

Two simulated cards exist, **in tests only**:

**Rust `TestCard`** (`crates/tapvault-core/src/testcard.rs`, compiled only for
the core crate's own tests or with the `test-card` feature, which the contract
enables only as a dev-dependency, so it is not in the shipped wasm). It
produces SDADs directly, without APDUs:

- `TestCard::default()`: 1024-bit key, e = 3. `TestCard::large()`: 1984-bit,
  e = 65537 (the most expensive key EMV allows; used for worst-case gas).
- `sign_challenge`: format 05 over a 4-byte UN (DDA/CDA style).
- `sign_online_fdda`: format 95 over 19 bytes: UN, amount, currency 0356
  (INR), 7 bytes of `9F69` (Visa online fDDA style).
- `sign_with_format`: any format byte, for the format checks.

**Kotlin `SimCard`** (`android/core/src/test/kotlin/app/tapvault/core/SimCard.kt`,
keys in `SimKeys.kt` from `tools/gen_sim_card_keys.py`). It answers real APDUs:
PPSE and application SELECT, GET PROCESSING OPTIONS, READ RECORD, INTERNAL
AUTHENTICATE and GENERATE AC, with a test CA → issuer → card certificate chain.
Common to all profiles: RID `F054415056`, AID `F0544150560001`, label
`TAPVAULT TEST` (network shows as `unknown`), CA index `01` (added to the CA
list only in tests, through `SimCard.TEST_CA`), test PAN ending `1234`, and the
same 1024-bit e = 3 card key as the Rust `TestCard`.

| Profile | Behaves like | AIP | PDOL | How it signs | Terminal data | Format |
|---|---|---|---|---|---|---|
| `DDA` | Classic DDA card | `2000` | `9F66 04, 9F37 04` | INTERNAL AUTHENTICATE, DDOL `9F37 04` | 4 bytes (UN) | 05 |
| `VISA_FDDA` | Visa payWave fDDA | `2000` | TTQ, amount, other amount, country, TVR, currency, date, type, UN | Signature (`9F4B`) in the GPO answer; fresh `9F69` in SFI 3 record 1 (the last AFL record); no INTERNAL AUTHENTICATE or GENERATE AC | 19 bytes: UN 4 + amount 6 + currency 2 + `9F69` 7 | 95 when the TTQ asks for an online cryptogram, else 05 |
| `MASTERCARD_CDA` | Mastercard contactless | `1980` (CDA, no DDA) | none | GENERATE AC with CDA; declines a zero amount (AAC); no INTERNAL AUTHENTICATE | 4 bytes (UN) | 05 |

What the simulated cards prove: the APDU sequence, certificate recovery and
the static-data hash, all three signing paths and their fallbacks, reuse of the
stored recipe, the terminal-data layout the contract checks, and that reports
and traces never contain the PAN.

What they **cannot** prove:

- That real Visa, Mastercard and RuPay cards answer the same way: real PDOL and
  CDOL contents, AFL layouts, where `9F69` lives, which cryptogram a card
  returns for a given TTQ and amount, whether INTERNAL AUTHENTICATE works over
  NFC. They were built from the EMV specs and public captures, not from a tap.
- The real CA keys: the simulated chain uses a test CA, so the production keys
  in `android/core/src/main/resources/capk.txt` are never exercised.
- Anything about RuPay (NPCI's contactless specs aren't public).
- NFC itself: antenna position, field strength, a card moving away, Android's
  `IsoDep`/`Tag` lifecycle and reader mode, the 5-second command timeout.
- The phone key: tests use a software key, never the Keystore, the fingerprint
  prompt, TEE behaviour or Android 9/10.
- The app's screens and view model.
- Quirks the simulated card never produces (GET RESPONSE `61xx`, wrong length
  `6Cxx`, format-1 GPO answers, several applications). The code handles them,
  but no test covers them.

Generators (each prints to stdout): `tools/gen_test_card.py` (the `TestCard`
key constants, pasted into `testcard.rs`; standard library),
`tools/gen_sim_card_keys.py` (redirect into `SimKeys.kt`; reuses
`gen_test_card.py`), `tools/gen_emv_vectors.py` (redirect into
`crates/tapvault-core/tests/emv_vectors.json`; needs the Python
`cryptography` package). All private keys in them are public on purpose:
never enrol a simulated card in a wallet that holds anything of value.

### Rules for running tests

- **Never run two Gradle builds of the same project at once.** That includes a
  second terminal, `android/build-apk.sh`, an IDE sync, and anything else
  that starts `./gradlew` in `android/`. Concurrent runs share
  `android/.gradle` and the build directories; on 12 Nov a concurrent run made
  a test task report a false failure. `./gradlew --status` lists running
  daemons and whether one is `BUSY`.
- Rust and Gradle runs don't interfere. `cargo near build` rewrites
  `target/near/tapvault_contract/tapvault_contract.wasm`, which the live test
  deploys only when `TAPVAULT_WASM` points at it.
- Live tests use shared resources: the faucet's per-IP quota and the sponsor's
  NEAR. Don't loop them.
- Use JDK 17 for Gradle and the pinned Rust toolchain.

## Real-phone test plan

For the tester holding the phone. The goal is to find out whether real cards
and a real phone do what the simulated tests say. Every step lists what to tap,
what you should see (exact screen text), what to record, and what to send back
if it goes wrong.

Please read the safety notes first:

- **Testnet only.** No real money moves. Test NEAR has no value.
- **Your card is never charged.** No PIN is asked and nothing is sent to your
  bank. Each tap is like a contactless payment that is started and abandoned:
  the card's transaction counter goes up and the chip may compute a cryptogram
  that is never sent anywhere. Some attempts show the card an amount of 1.00
  INR, which is never charged. Don't tap more often than the plan needs.
- **Your card number stays on the phone.** Reports show only the last four
  digits.
- **Don't uninstall the app or clear its data during the test.** That deletes
  the phone key, and a wallet without its phone key can never approve anything
  again.

### Before you start

| Need | Details |
|---|---|
| Android phone | Android 9 or newer, with NFC. Note maker, model, Android version |
| NFC on | Android Settings → Connected devices → NFC (the path varies by brand) |
| Screen lock and fingerprint | A PIN, pattern or password, and at least one enrolled fingerprint. Android 9/10 need the fingerprint; on Android 11+ the PIN/pattern also works in the prompt |
| Internet | Wi-Fi or mobile data. Keep the phone's date and time set to automatic (Execute buttons use the phone's clock) |
| Several contactless cards | As many different ones as you have: Visa, Mastercard and RuPay; debit and credit; different banks. Look for the contactless symbol. Take cards out of RFID-blocking sleeves; remove thick or metal phone cases |
| The APK | `app-debug.apk` from the developer, built from the 12 Nov code or later. Ask whether it has a sponsor built in (it decides where test NEAR comes from when the faucet is busy) |
| A second testnet account (the receiver) | See below |
| Vibration on | Optional: the phone buzzes once when the card has been read |
| Time | About 1.5 hours, including one 1-hour wait; 2.5 hours with the optional step 14 |
| A way to send text and screenshots | Messaging app or email |
| Optional | A computer with `adb`, `curl` and `jq` |

If an older Tapvault build is installed, uninstall it first. Wallets created
before 12 Nov run the previous contract build (it rejects Visa online
signatures) and can't be upgraded.

**Getting a receiver account.** Any existing NEAR testnet account works; the
app checks that it exists. Options, simplest first:

- An account you already have on testnet (for example from a testnet web wallet).
- Create one with the NEAR CLI (`near-cli-rs`); it uses the same faucet:

  ```bash
  near account create-account sponsor-by-faucet-service my-receiver-123.testnet autogenerate-new-keypair save-to-keychain network-config testnet create
  ```

- If you have nothing else: the sponsor account `tapvault-skyyy.testnet`. It
  exists, and test NEAR sent there goes back into the pool that funds new
  wallets.

Write the receiver down; the steps call it `<receiver>`.

### What to send back

Each step says which of these to collect when something fails:

| Code | What | How |
|---|---|---|
| **S** | Screenshot of the screen and the exact message | The result box at the top ("That didn't work", with a red icon) stays until you tap OK or move to another screen |
| **L** | The in-app Log | Activity screen, section "Log" (newest first, last 60 lines). Screenshot every line since the step began. The Log is kept in memory only: take it before closing the app. It can only be opened after a wallet exists |
| **R** | The card report | Card check → "Check a card" with the card involved → "Share report" |
| **W** | Wallet account name and links | Settings → "Wallet account" (the name is shown under it), or during setup the "Wallet account: …" line on the Setup screen; any "View on explorer ↗" link from a result box |
| **V** | A short video | Film the tap with another phone if the problem is about holding the card |
| **C** | Crash details (the app closes by itself) | Time of the crash and what you tapped. With a computer: `adb logcat -b crash -d > tapvault-crash.txt` |

The app writes nothing to the Android system log; the in-app Log is the only
log. Only a crash is recorded by Android itself (**C**).

### Steps

#### 1. Install

1. Copy `app-debug.apk` to the phone and open it; allow your file manager or
   browser to install unknown apps when asked. Or, from a computer:
   `adb install -r app-debug.apk` (on Xiaomi phones, also enable "Install via
   USB" in Developer options).
2. If Play Protect, MIUI or ColorOS warns or offers to scan, let it scan and
   install anyway.
3. Open **Tapvault**.

Expect the Welcome screen: "tapvault", "Your bank card is your hardware
wallet.", two tiles "My bank card" (Visa · Mastercard · RuPay, already
selected) and "Check it first" (See if your card can sign), a "Spending limits"
box with "At once (NEAR)" = 1 and "Per day (NEAR)" = 2, the button "Create my
wallet" and the line "NEAR testnet · accounts and test NEAR come from the
faucet". With NFC off you also see "NFC is off. Turn it on in Android settings
(Connected devices → NFC)."; turn it on.

Record: phone model, Android version, install method, any installer warning.
On failure: **S**.

#### 2. Card check, with every card

For each card:

1. Welcome → **Check it first**. (After setup: the card icon in the bottom
   bar, or the card button on Home.)
2. Tap **Check a card**. A sheet says "Hold the card you want to check".
3. Hold the card flat against the back of the phone, near the camera, and keep
   it still. The sheet changes to "Keep holding the card…"; keep holding until
   it closes (2–3 seconds; the phone may buzz).
4. Read the result, then tap **Share report** and send the text with a note
   of which card it is (bank, network, debit or credit). Share before checking
   the next card: each check replaces the previous report.

Expect a box "`<network>` •••• `<last 4 digits>`" with a badge **Can sign** or
**Can't sign**, rows "Offline auth", "CA key index", "Issuer key", "Card key",
"Signs with", "Challenge format", and notes below. A card that can sign has the
note "signs with …: this card can be a Tapvault signer". Expected by network
(none of this is verified yet):

| Network | Expected "Signs with" |
|---|---|
| Visa | signature in GET PROCESSING OPTIONS (fDDA) |
| Mastercard | signature in GENERATE AC (CDA) |
| Some cards of any network | INTERNAL AUTHENTICATE (DDA) |
| RuPay | Unknown. The report is what we need |

**Privacy check, every card:** the shared text shows the card number only as
`card: ****<last 4>`. Your full card number, your name or the expiry date must
not appear anywhere in it. If they do, stop and report it. (A note
`issuer certificate expired MM/YY` is about the bank's certificate, not your
card's expiry date.)

Record per card: bank, network, debit/credit, badge, "Signs with", "Card key".
On failure (an error box instead of a report, or a crash): **S** (exact text),
**C** for crashes, and say which card. Pass: every card gives a report and at
least one shows **Can sign**. If no card can sign, stop here and send all
reports: setup can't work with these cards. See
[TROUBLESHOOTING.md](TROUBLESHOOTING.md#reading-the-card-check-report) for what
the report lines mean.

#### 3. Create a wallet

Use a card that showed **Can sign**. Note which one.

1. Back to Welcome (the back arrow at the top left of Card check). Leave the
   limits at 1 and 2.
2. Tap **Create my wallet**. The Setup screen ("Setting up", "Almost there")
   lists six steps. Any earlier Card check report is cleared; once the
   accounts exist, "Wallet account: tv-…" appears under the steps.
3. Wait through "Creating your phone key in secure hardware" and "Creating
   your wallet account on NEAR testnet". The second can take a minute or two
   (the faucet).
4. The sheet "Hold your bank card to the back of the phone to add it" appears:
   hold the chosen card until the sheet closes.
5. The fingerprint prompt (title "Tapvault", text "Your phone key signs only
   what you approve here.") says **Create your Tapvault wallet**: approve with
   your fingerprint (or PIN/pattern).
6. Prompt `Add Visa card ****1234 as a signer` (your network and last four
   digits; a Mastercard shows `Add Mastercard ****1234 as a signer`, a RuPay
   card `Add RuPay card ****1234 as a signer`): approve.
7. Prompt **Lock the wallet: up to 1 NEAR at once, 2 NEAR a day**: approve.

Expect:

- The steps turn "Done" in this order: Phone key in secure hardware, Wallet
  account on NEAR, Card read and signed, Wallet contract deployed, Card added
  as a signer, Limits set, account locked (the last one flashes by as Home
  opens). The card is read **before** the contract is deployed.
- Then Home with the box **Your wallet is ready** / "Funds now move only with
  your phone and your card." / "View on explorer ↗".
- Home: "Wallet (NEAR)" about 6.6 (faucet-funded) or about 2.1 (sponsor-funded),
  badge "1 NEAR instant now", and below the tabs "+ … NEAR held for the wallet
  contract's storage" (a little over 3.34).
- Signers tab: "This phone" (P-256) and "Bank card" (RSA), "2 of 2 must
  approve every payment". Limits tab: "Up to 1 NEAR at once", "2 NEAR per day",
  "Bigger payments wait 1 h".
- Settings: "Phone key" shows "Trusted Execution Environment" (Android 12+) or
  "secure hardware" (older); "Card" shows "Bank card over NFC · `<method>`";
  "Delays" shows "transfers 1 h · changes 1 h · recovery 24 h".
- Activity → Log includes these lines (each starts with the time):
  - `phone key stored in …`
  - `wallet tv-…, fee account tv-…-gas…`
  - `card <network> ****<last 4> read (<bits>-bit key, <method>)`
  - `wallet contract deployed: …`
  - `card ****<last 4> added as a signer`
  - **`wallet account locked: no keys left`**

Record: card used; wallet account (Settings → "Wallet account"); whether the
Log says "faucet unavailable (…); funding … from …" (sponsor used); minutes from
"Create my wallet" to "Your wallet is ready"; the "Phone key" label. The Card
check tab now shows the report of the card you just added (unless the app was
closed during setup): tap **Share report** and send it.

If it fails: **S**, and for card problems tap **Share card report** under the
report on the Setup screen and send the text. For **W**, note the "Wallet
account: …" line on the Setup screen. **L** can only be collected once setup
has finished: during setup neither Settings nor the Log can be opened. Then:

- "This card can't sign for Tapvault: … Tap Continue setup to try another
  contactless card, or Start over." → tap **Continue setup** and hold another
  card that showed **Can sign**.
- Network or faucet errors → **Continue setup**. Each step checks the chain
  first and skips what already landed.
- Don't tap **Start over** unless asked: it deletes the phone key and the
  app's saved state. In a build with a sponsor it also deletes the fee
  account, and the wallet account too if nothing was deployed yet (their NEAR
  goes to the sponsor), and lists them in a "Started over" box; without a
  sponsor both accounts stay on testnet. Its dialog says which applies.
- The Log line "WARNING: wallet still has keys: …" is a failure even if setup
  finished.

#### 4. Check the wallet on the explorer

Tap **View on explorer ↗** in the result box (or Settings → "Wallet account").
The page is `https://testnet.nearblocks.io/address/<wallet>`.

Expect: the account exists and holds the balance; it has a contract and **no
access keys**; its first transactions (deploy with `new`, `setup_add_signer`,
`setup_finalize`) succeeded.

Exact check (on a computer, or send the account name to the developer):

```bash
W=tv-0123456789.testnet      # your wallet account
RPC=https://rpc.testnet.fastnear.com
curl -s $RPC -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":"1","method":"query","params":{"request_type":"view_account","finality":"final","account_id":"'$W'"}}' | jq -r .result.code_hash
# 5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf
curl -s $RPC -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":"1","method":"query","params":{"request_type":"view_access_key_list","finality":"final","account_id":"'$W'"}}' | jq .result.keys
# []
curl -s $RPC -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":"1","method":"query","params":{"request_type":"call_function","finality":"final","account_id":"'$W'","method_name":"get_wallet","args_base64":"e30="}}' | jq -r '.result.result | implode' | jq .
# setup_mode false, frozen false, threshold 2, two signers (P256, EmvCard),
# delays 3600 / 3600 / 86400, one limit for "Near" (1 and 2 NEAR in yoctoNEAR)
```

Record: wallet account, code hash, number of keys. Fail: a different code hash
(the APK isn't the 12 Nov build) or any access key listed.

#### 5. Send 0.1 NEAR within the limit

1. Home → **Send**.
2. Amount `0.1` (a comma works too); "NEAR account": `<receiver>`. The box below
   shows, label then value: "This payment" Instant, "Instant limit" 1 NEAR,
   "Instant allowance left" 1 NEAR, "Network fee" paid by your fee account,
   "Approvals" phone + card.
3. Tap **Approve, then tap card**.
4. Prompt **Send 0.1 NEAR to `<receiver>`**: check amount and receiver, approve.
5. "Waiting for the card challenge" (a second or two), then the sheet **Tap
   your card to approve sending 0.1 NEAR**: hold the enrolled card until it
   closes.
6. "Submitting".

Expect the box **Sent 0.1 NEAR** / "to `<receiver>`" / "View on explorer ↗". On
the explorer the transaction is signed by the fee account (`tv-…-gas…`), sent
to the wallet account, and has two calls, `approve_card` and `execute`; its
logs include `EVENT_JSON` events `approved`, `ready` and `executed`; a 0.1 NEAR
transfer reaches `<receiver>`. The Send screen stays open: use the back arrow.
Home shows about 0.1 NEAR less within about 10 seconds. "1 NEAR instant now"
doesn't change (1.9 NEAR of the daily allowance is left, more than the 1 NEAR
per-payment limit).

If the box says **Approved, waiting** instead ("The wallet didn't treat this
as within your limits (the phone's clock may be off). …"), the contract didn't
count the payment as instant and it now waits 1 h: check that date and time
are automatic, and send **S** and **L**. Execute it from Activity later, or
cancel it.

Record: the transaction link; seconds from tapping the button to "Sent";
Settings → "Card" method. On failure: **S**, **L**, **W**, **R**.

#### 6. Send over the instant limit

1. Home → **Send** → amount `1.5`, "NEAR account" `<receiver>`. The box shows
   "This payment" Waits 1 h.
2. **Approve, then tap card** → prompt **Send 1.5 NEAR to `<receiver>`** → sheet
   **Tap your card to approve sending 1.5 NEAR** → hold the card.

Expect **Approved, waiting 1 h** / "This is over your instant limit. In 1 h,
open Activity and tap Execute. Until then you can cancel it or freeze the
wallet." The balance doesn't change. On Home the Activity (clock) button has a
dot and the Activity tab lists "#`<n>` Send 1.5 NEAR to `<receiver>`" with
"Approved · can execute in 0 h 59 min".

If the wallet has less than 1.5 NEAR to send ("The wallet only has … NEAR
available to send"), use `1.2`: still over the 1 NEAR limit.

Record: transaction link (it holds only `approve_card`), proposal number. On
failure: **S**, **L**, **W**.

#### 7. Activity: cancel it

1. Open **Activity** (the clock button at the top right of Home, or in the
   bottom bar). The entry shows "Send 1.5 NEAR to `<receiver>`", "Approved · can
   execute in 0 h …", the tag "#`<n>`" and a **Cancel** button (no Execute yet).
2. Tap **Cancel** → prompt **Cancel: Send 1.5 NEAR to `<receiver>`** → approve.

Expect **Cancelled** / "Send 1.5 NEAR to `<receiver>`"; the list shows "All
clear" / "Nothing waiting for approval"; the balance is unchanged. On failure:
**S**, **L**, **W**.

#### 8. Tap a different card when the enrolled one is asked for

1. Send `0.1` to `<receiver>` again and approve the prompt.
2. At **Tap your card to approve sending 0.1 NEAR**, hold a **different**
   card. If you can, try one of the same network as the enrolled card first,
   then one of another network.

Expect one of:

- **Same network, a card that signs the same way as the enrolled one:** the
  sheet comes back with "That's not the card in this wallet. Tap the card you
  added." Hold the enrolled card: **Sent 0.1 NEAR**.
- **Same network, a card that signs another way:** the box "That didn't work"
  with "the card signed, but not the way it did when it was added; tap it
  again", then the "Payment #`<n>` is saved …" text. Finish the payment as in
  the next case.
- **Another network, or a card that can't sign this way:** the box "That
  didn't work" with "the card didn't sign (…)" (for another network usually
  "card answered SELECT `<AID>` with 6A82 (not found)"), then "Payment #`<n>` is
  saved and waits 10 minutes for your card: send the same amount again, or
  approve or cancel it in Activity." Within 10 minutes, tap **Approve, then tap
  card** again with the same amount and receiver: there is **no** prompt this
  time (Log: "finishing unfinished payment #`<n>`"); hold the enrolled card →
  **Sent 0.1 NEAR**.

Never acceptable: a payment going out after only the wrong card was tapped, or
two 0.1 NEAR payments from this step. Three failed tries that include a
wrong-card tap end with "That's not the card in this wallet. Use the card you
added during setup." followed by the "Payment #`<n>` is saved …" text; the
enrolled card then finishes the same payment as above.

Record: which cards, the exact messages, Log lines ("a different card was
tapped", "finishing unfinished payment #…"). On failure: **S**, **L**, **W**,
and **R** for the other card. If the **enrolled** card is answered with "That's
not the card in this wallet" (a bug) or "the card signed, but not the way it
did when it was added" (it answered differently than at setup), send **L**
and **R** of the enrolled card.

#### 9. Freeze with card

1. Home → the snowflake button (between Receive and the card button; its
   accessibility label is "Freeze with card").
2. Dialog **Freeze the wallet?** "One card tap stops every transfer. Unfreezing
   needs your phone and card, then waits 1 h." → **Freeze**.
3. Sheet **Tap your card to FREEZE the wallet** → hold the enrolled card.

Expect no fingerprint prompt, then **Wallet frozen** / "Frozen with a card
tap. Unfreezing needs your phone and card, plus a delay." Home shows the badge
"Frozen", a box "Wallet frozen" with the tag "1 h to unfreeze", the text "No
transfers can go out. Unfreezing needs your phone and your card, then waits."
and the button **Request unfreeze**. "Send" and the snowflake are dimmed.

Record: transaction link. On failure: **S**, **L**, **W**.

#### 10. Request unfreeze

1. Home → **Request unfreeze** → prompt **Unfreeze the wallet (takes effect
   after 1 h)** → approve.
2. Sheet **Tap your card to approve unfreezing** → hold the enrolled card.

Expect **Unfreeze approved** / "It can execute in 1 h (Activity tab)."
Activity lists "Unfreeze" with "Approved · can execute in 0 h 59 min" and
**Cancel**. The wallet stays frozen until step 12. Note the time.

If the card step fails after the prompt, the request is already saved, though
the error box doesn't say so: within 10 minutes, tap **Request unfreeze** again
(no prompt this time: it finishes the same request) or use Activity →
**Approve · card**. Once that request's card window has closed, **Request
unfreeze** creates another request (which counts towards the phone's 24
proposals a day). With NFC off it stops with the NFC message before the
prompt. On failure: **S**, **L**, **W**.

#### 11. Failure drills, part A (during the 1-hour wait)

Use Card check (the card icon in the bottom bar).

a. **Pull the card away early.** **Check a card**, hold the card, and pull it
   away as soon as "Keep holding the card…" appears. Expect the sheet to come
   back with "The card moved away too soon. Tap again and keep it still until
   this screen closes." (or "The card connection dropped. Tap again and keep
   it still until this screen closes.", or "Tap the card again and keep it
   still until this screen closes."). Hold the card properly: the report
   appears. Three failed reads in a row end with "Couldn't read the card. Hold
   it flat against the back of the phone, near the camera, for 3 seconds." Log:
   "card moved away (attempt 1); hold it still" or "card link error: …; tap
   again".

b. **NFC off.** Turn NFC off in Android settings, come back, tap **Check a
   card**. Expect no sheet and the box "That didn't work" / "Turn on NFC in
   Android settings (Connected devices → NFC), then try again." Turn NFC on
   again; **Check a card** works.

c. **Cancel a tap.** **Check a card** → **Cancel** on the sheet. Expect "Card
   tap cancelled".

d. Optional, **no tap.** **Check a card** and wait 2 minutes. Expect "No card
   was tapped. Try again and hold the card flat against the back of the phone."

Record the exact texts. On failure: **S**, **V** for holding problems, **C**
for crashes.

#### 12. Execute the unfreeze (1 hour after step 10)

1. Activity: the "Unfreeze" entry shows "Approved · ready: tap Execute".
2. Tap **Execute**.

Expect no prompt and no tap (the approvals are already on-chain), then
**Done** / "Change settings: Unfreeze". Home: no "Frozen" badge; "Send" works
again. Any other open entry now shows "Void: signers changed" with **Clean
up**: executing a settings change voids every other pending proposal.

If you see "Delay has not elapsed", the phone's clock is ahead: wait a few
minutes and set date and time to automatic. On failure: **S**, **L**, **W**.

#### 13. Failure drills, part B (sending)

a. **Cancel the fingerprint.** Send `0.1` → **Approve, then tap card** →
   cancel the prompt (back gesture, or "Cancel" on Android 9/10). Expect "That
   didn't work" / "Approval cancelled: `<your phone's text>`". Activity: nothing
   new.

b. **Pull the card away during a payment.** Send `0.1` → approve → at "Keep
   holding the card…" pull the card away. Expect the sheet to come back with
   "The card moved away too soon. …"; hold the card → **Sent 0.1 NEAR**. If
   three tries fail: "Couldn't read the card. …" followed by "Payment #`<n>` is
   saved and waits 10 minutes for your card: …"; send the same amount to the
   same receiver again (no prompt), hold the card → **Sent 0.1 NEAR**. Exactly
   one 0.1 NEAR payment.

c. **Airplane mode during a send.** Send `0.1` → approve the prompt → while
   "Waiting for the card challenge" or the tap sheet is showing, switch
   airplane mode on (quick settings); if the sheet is open, hold the card.
   Expect no crash. Within a few minutes (the app retries three RPCs; there is
   no cancel button while it waits) a box "That didn't work" with a network
   message, for example "can't reach `<host>`: …", or "The network didn't confirm
   transaction `<hash>` in time. It may still go through: check Activity (or
   `https://testnet.nearblocks.io/txns/<hash>`) before trying again." Home shows
   "Can't reach NEAR testnet right now. Showing the last known state;
   retrying." Some phones also turn NFC off in airplane mode; then you get the
   NFC message instead (or, if the sheet was already open, "No card was
   tapped. …" after 2 minutes), followed by "Payment #`<n>` is saved …".

   Then switch airplane mode off and wait until the banner goes. Activity shows
   either nothing (the payment went through; the balance is 0.1 lower) or an
   entry "Send 0.1 NEAR to `<receiver>`" (tag "#`<n>`") with "Waiting for
   approvals" (the proposal was already on-chain when "Waiting for the card
   challenge" appeared). In the second case, within 10 minutes of the prompt, send the
   same amount to the same receiver again (no prompt; Log "finishing
   unfinished payment #`<n>`") and hold the card → **Sent 0.1 NEAR**; or tap
   **Cancel**.

   Pass: a readable error, no crash, and at most one 0.1 NEAR payment for this
   attempt (check `<receiver>` on the explorer).

Record for each: the moment you acted, the exact message, the Log, what
Activity showed afterwards, the transfers `<receiver>` got. On failure: **S**,
**L**, **W**, **V**.

#### 14. Optional: a delayed payment that executes

Send `1.5` (or `1.2`) → **Approved, waiting 1 h** → 1 hour later, Activity
shows "Approved · ready: tap Execute" → **Execute** → **Done** / "Send 1.5 NEAR
to `<receiver>`". The balance drops by 1.5 and `<receiver>` receives it. A
sponsor-funded wallet may not have enough left; top it up first (Home →
Receive shows the account to send testnet NEAR to).

#### 15. Finish

- Leave the app installed; don't clear its data.
- Send the test report (template below) with every card report, the
  screenshots and the wallet account name.

## Acceptance checklist

| # | Check | Pass when | Result |
|---|---|---|---|
| 1 | Install | App opens on Welcome; no crash | |
| 2 | Card check | Every card gives a report; at least one **Can sign**; shared reports show only the last 4 digits | |
| 3 | Setup | Home opens with "Your wallet is ready"; Log has "wallet account locked: no keys left" and no "WARNING" line | |
| 4 | Phone key | Settings → "Phone key" is "Trusted Execution Environment" or "secure hardware", not "software (no secure hardware!)" | |
| 5 | Explorer | `code_hash` is `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`; no access keys | |
| 6 | Prompt text | Every prompt named the exact amount and receiver | |
| 7 | Instant payment | "Sent 0.1 NEAR"; one transaction with `approve_card` + `execute`; `<receiver>` got 0.1 | |
| 8 | Over-limit payment | "Approved, waiting 1 h"; balance unchanged | |
| 9 | Cancel | "Cancelled"; entry gone; balance unchanged | |
| 10 | Wrong card | Nothing sent with the wrong card; the enrolled card then finished the same payment | |
| 11 | Freeze with card | "Wallet frozen" without a prompt; Send dimmed | |
| 12 | Request unfreeze | "Unfreeze approved"; entry counts down | |
| 13 | Pull away (Card check) | Retry hint shown; report after retry | |
| 14 | NFC off | NFC message, no tap sheet | |
| 15 | Execute unfreeze | "Done"; wallet unfrozen | |
| 16 | Fingerprint cancelled | "Approval cancelled: …"; nothing created | |
| 17 | Pull away (payment) | Exactly one payment after retry or re-send | |
| 18 | Airplane mode | Readable error, no crash, at most one payment, recovers afterwards | |
| 19 | Delayed payment (optional) | "Done" after 1 h; `<receiver>` got the amount | |
| 20 | No crashes | The app never closed by itself | |

## Test report template

Copy, fill in, and send with the attachments.

```text
TAPVAULT REAL-PHONE TEST REPORT

Date, time and time zone:
Tester:
APK file name, who built it, build date (commit if known):
Sponsor built in (yes / no / don't know):

Phone maker and model:
Android version and security patch:
Screen lock (PIN / pattern / password):     Fingerprint enrolled (yes/no):     Face unlock (yes/no):
Where the NFC antenna is (where the card worked):
Network (Wi-Fi / mobile data):

Wallet account (Settings > Wallet account):
Funded by (faucet / sponsor, from the Log):
Receiver account used:

CARDS (attach every Share report)
#  | Bank | Network | Debit/credit | Badge (Can sign / Can't sign) | Signs with | Report sent
1  |      |         |              |                               |            |
2  |      |         |              |                               |            |
3  |      |         |              |                               |            |

Card used for the wallet:

STEPS (Pass / Fail / Not run, plus notes and links)
1  Install:
2  Card check:
3  Create wallet (minutes taken):
4  Explorer (code hash, keys):
5  Send 0.1 (tx link, seconds to "Sent"):
6  Over the limit (tx link):
7  Cancel:
8  Different card (which cards, messages):
9  Freeze with card (tx link):
10 Request unfreeze (time):
11 Drills A (a pull away, b NFC off, c cancel, d no tap):
12 Execute unfreeze:
13 Drills B (a fingerprint cancel, b pull away, c airplane mode):
14 Delayed payment (optional):

ACCEPTANCE CHECKLIST: numbers that failed:

FAILURES (one block per failure)
Step:
What I tapped:
What I expected:
What happened (exact text):
Attached (S / L / R / W / V / C):
Explorer links:
```
