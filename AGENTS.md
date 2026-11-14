# AGENTS.md

The first file to read if you are a coding agent or a new contributor.

## What this is

- Tapvault is a NEAR wallet where every payment needs two co-signers: an ordinary contactless bank card (its EMV chip's RSA key) and a P-256 key in the phone's Android Keystore, unlocked per use by fingerprint or screen lock.
- A Rust contract (`contract/`, security logic in `crates/tapvault-core/`) is deployed to each wallet's own account; it verifies both signatures on-chain and enforces limits, delays and freeze. The account's key is deleted at the end of setup.
- The Android app (`android/app`) creates the wallet, reads the card over NFC and sends every transaction. Its client logic lives in `android/core`: plain Kotlin with no Android APIs, so the same code is tested on the JVM and against live testnet.
- NEAR testnet; the app has a Mainnet/Testnet switch (Settings → Network) but mainnet is off in every build until real-card testing and a contract review (`tapvault.mainnet`, invariant 7). Hackathon project: NEAR HACKATHON, submission deadline 14 Nov 2026, 23:59 UTC.
- Status (14 Nov 2026): all offline tests pass with simulated cards; **nothing has run against a real bank card or a real phone's NFC yet**. The 14 Nov contract build (v0.3: wallets without a card) passes the live testnet tests (`PhoneOnlyE2ETest`, `TestnetE2ETest` ×3); adding the card after the 1 h delay hasn't run live (docs/STATUS.md).

## Read first

1. [docs/README.md](docs/README.md): the doc index and reading paths.
2. [docs/STATUS.md](docs/STATUS.md): what is verified, what isn't, known issues.
3. Then by task:
   - Contract or core logic: [docs/DESIGN.md](docs/DESIGN.md), [docs/CONTRACT.md](docs/CONTRACT.md).
   - Card code (EMV, NFC, CA keys): [docs/CARDS.md](docs/CARDS.md).
   - Android app: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/ANDROID.md](docs/ANDROID.md).
   - Testing on a phone: [docs/TESTING.md](docs/TESTING.md), [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
   - Words you don't know: the glossary in [docs/OVERVIEW.md](docs/OVERVIEW.md#glossary).

## Repository layout

| Path | What it is |
|---|---|
| `crates/tapvault-core/` | Dependency-free Rust: `bigint` (RSA public op), `sha1`, `sha256`, `emv` (SDAD check, formats `05`/`95`), `webauthn`, `domain` (every signed message), `policy` (limits, delays, approvals, rate limit), `testcard` (feature `test-card`, tests only) |
| `crates/tapvault-core/tests/` | EMV vector tests and `emv_vectors.json` (from `tools/gen_emv_vectors.py`) |
| `contract/src/` | NEAR contract: `lib.rs` (state, `new`, constants), `types.rs`, `auth.rs` (signature checks), `setup.rs`, `proposals.rs`, `execute.rs`, `guard.rs` (cancel, freeze, close_stale), `views.rs`, `events.rs`, `error.rs` |
| `contract/tests/` | `flows.rs` (32 tests, mocked runtime; 5 for v0.2, 3 for v0.3 phone-only), `vectors.rs` (writes/checks the Kotlin vectors), `sandbox.rs` (compiled wasm on a local node, opt-in; two tests: the full flow, and a phone-only wallet that adds its card) |
| `contract/res/` | The shipped contract build `tapvault_contract.wasm` and its README (hashes, how to replace it) |
| `android/core/src/main/kotlin/app/tapvault/core/` | Kotlin client: `Network.kt` (testnet / mainnet: RPCs, index, explorer, suffix, faucet), `NearClient.kt`, `Wallet.kt` (`TapvaultWallet`, `Onboarding`), `Model.kt`, `Borsh.kt`, `Domain.kt`, `Emv.kt` (`EmvSession`, `CardService`), `CardBook.kt` (the cards a phone knows per wallet), `Integrity.kt` (wallet check), `Watch.kt` (watchtower rules), `Receivers.kt` (receivers paid before, look-alikes), `Recovery.kt` (recover on a new phone), `Guardian.kt` (guardian codes), `Tokens.kt` (tokens), `Intents.kt` (NEAR Intents 1Click client, prices), `Swap.kt` (quote checks, chain addresses, swap records, Ramp links), `Tlv.kt`, `P256.kt`, `Bytes.kt` |
| `android/core/src/main/resources/capk.txt` | EMV CA public keys (RID, index, exponent, modulus) |
| `android/core/src/test/` | `EmvTest`, `MultiCardTest`, `Phase2Test`, `Phase3Test`, `Phase4Test`, `VectorsTest`, `RpcProbeTest`, `TestnetE2ETest`, `RecoveryE2ETest`, the `SimCard` fixture (any key and card number; `TestCards.random()` for a second card) and its `SimKeys`, `resources/vectors.json` |
| `android/app/src/main/kotlin/app/tapvault/` | App: `MainActivity.kt` (NFC reader mode, `IsoDepTransceiver`, `FLAG_SECURE`, app-lock hooks, alert intents), `AppViewModel.kt` (every flow), `Keystore.kt` (`KeystoreSigner`), `Store.kt`, `PendingCard.kt`, `Screens.kt`, `Security.kt` (Security Center, lost card), `Watchtower.kt` (`Watchtower`, `WatchJob`, `AppLock`), `Join.kt` (Recover a wallet, Be a guardian, add-guardian, fingerprint-only and allowlist sheets), `Onboarding.kt`, `TokensUi.kt`, `Trade.kt` (Swap, Bridge, Buy, Sell), `NetworkUi.kt`, `Components.kt`, `Glass.kt` (glass, tab bar, motion), `Theme.kt` |
| `android/app/build.gradle.kts` | App build: wasm hash check (`verifyWasm`, `copyWasm`), sponsor `BuildConfig` fields |
| `android/build-apk.sh` | Finds a JDK 17 and builds the debug APK |
| `tools/` | Python generators: EMV test vectors, the Rust test card key, the simulated card's key chain |
| `docs/` | Documentation ([docs/README.md](docs/README.md)) |
| `rust-toolchain.toml`, `.cargo/config.toml` | Rust pins (see Invariants) |

## Commands

Run them from the repository root unless a `cd` says otherwise.

### Rust

```bash
cargo test                                         # core (21 unit + 7 EMV vectors), contract flows (32) + vectors (1); no testnet needed
cargo test -p tapvault-core                        # core only
cargo test -p tapvault-contract --test flows       # contract flows only
cargo test -p tapvault-contract --test vectors     # fails if android/core/src/test/resources/vectors.json is stale
UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors    # rewrite vectors.json after changing signed bytes

# The contract compiled to wasm on a local nearcore 2.6.5, gas per step printed.
# Opt-in: it builds the wasm from the current source itself (near_workspaces::compile_project,
# not the file in contract/res) and downloads a sandbox node on first run.
cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture
```

Contract build (only when the contract really changed; see [Shipping a contract change](#shipping-a-contract-change)):

```bash
cargo install --locked cargo-near@0.17.0
cd contract && cargo near build non-reproducible-wasm
# -> target/near/tapvault_contract/tapvault_contract.wasm
```

### Kotlin (android/core)

```bash
cd android
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # macOS; elsewhere point it at a JDK 17
"$JAVA_HOME/bin/java" -version                       # must say 17
./gradlew :core:test                                 # EmvTest (15), MultiCardTest (7), NetworkTest (6), TokensTest (6, one live), SwapTest (10, one live: `TAPVAULT_INTENTS=1` asks 1Click for a dry quote), Phase2Test (8), Phase3Test (6), Phase4Test (5), Phase5Test (3), SecurityKeyTest (5), SeedWalletTest (9), VectorsTest (3); the live tests return at once and count as passed
```

### Live testnet tests

The live test deploys the shipped build, `contract/res/tapvault_contract.wasm`, unless `TAPVAULT_WASM` is set in your shell: `android/core/build.gradle.kts` passes your value through (give an absolute path) and records it as a test input, so changing it reruns the test.

```bash
cd android
TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*RpcProbe*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=DDA            ./gradlew :core:test --tests '*TestnetE2E*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=VISA_FDDA      ./gradlew :core:test --tests '*TestnetE2E*' --rerun
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=MASTERCARD_CDA ./gradlew :core:test --tests '*TestnetE2E*' --rerun
TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*RecoveryE2E*' --rerun   # setup with 2 cards, recovery on a new phone, old phone cancels, guardian
TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*PhoneOnlyE2E*' --rerun  # v0.3: wallet without a card, phone alone pays, add the card (+ SetThreshold 2)
```

| Variable | Meaning |
|---|---|
| `TAPVAULT_TESTNET=1` | Required, or both live tests return immediately (and pass) |
| `TAPVAULT_CARD_PROFILE` | How the simulated card signs: `DDA` (default), `VISA_FDDA`, `MASTERCARD_CDA` |
| `TAPVAULT_SPONSOR_ACCOUNT`, `TAPVAULT_SPONSOR_KEY` | Optional funded testnet account and its full-access secret key (`ed25519:…`), used when the faucet refuses. Both are needed: an account without a key is ignored |
| `TAPVAULT_FORCE_SPONSOR=1` | Skip the faucet and fund both accounts from the sponsor |
| `TAPVAULT_WASM` | Optional: absolute path of another wasm to deploy; default `contract/res/tapvault_contract.wasm` (see above) |
| `TAPVAULT_WAIT_DELAYS=1` | Optional: wait out the 1 h config delays (about 2 h 10 min) so the backup card is executed, pays with the phone, and card 1 is removed. Without it the run stops after the backup card's proposal is approved and refused early |

- `--rerun` matters: apart from `TAPVAULT_WASM`, Gradle doesn't count these variables as test inputs, so a repeat run with only different variables can be reported `UP-TO-DATE` and skipped.
- A real run prints `[e2e] …` lines ending in `[e2e] done. wallet: https://testnet.nearblocks.io/address/tv-e2e-…`. No `[e2e]` lines means it didn't run.
- Each `TestnetE2ETest` run creates two new testnet accounts (`tv-e2e-<10 hex>` and `tv-e2e-<10 hex>-gas`). The faucet allows about 10 per IP per 12 h, then answers HTTP 429; use the sponsor variables then.

### Android app

```bash
android/build-apk.sh
# -> android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -b crash -d        # crash stack traces; the app itself writes nothing to logcat
```

The app's own log is in memory: Settings → Advanced → Technical log (last 60 of 200 lines).

### Checking the shipped build and a wallet on chain

```bash
shasum -a 256 contract/res/tapvault_contract.wasm
# 778a310ad4db8a87d6d0d52e8509d7ea4f5014782a0832106ab1cd2946e582c6

# base58 form (NEAR's code_hash): 93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy
python3 - contract/res/tapvault_contract.wasm <<'EOF'
import hashlib, sys
d = hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()
a = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
n, s = int.from_bytes(d, 'big'), ''
while n:
    n, r = divmod(n, 58)
    s = a[r] + s
print('1' * (len(d) - len(d.lstrip(b'\0'))) + s)
EOF

# A wallet's code_hash, and its access keys (should be an empty list after setup)
curl -s https://rpc.testnet.fastnear.com -H 'Content-Type: application/json' -d \
  '{"jsonrpc":"2.0","id":"x","method":"query","params":{"request_type":"view_account","finality":"final","account_id":"tv-e2e-cc807a553f.testnet"}}'
curl -s https://rpc.testnet.fastnear.com -H 'Content-Type: application/json' -d \
  '{"jsonrpc":"2.0","id":"x","method":"query","params":{"request_type":"view_access_key_list","finality":"final","account_id":"tv-e2e-cc807a553f.testnet"}}'
```

## Environment

| Need | Details |
|---|---|
| JDK 17 | Gradle 8.11.1 (wrapper) with AGP 8.7.3 and Kotlin 2.1.0 needs JDK 17; it failed on JDK 21 and 25. `android/build-apk.sh` checks `$JAVA_HOME`, `/usr/libexec/java_home -v 17`, Homebrew `openjdk@17` and common Linux paths, and verifies the version |
| Android SDK | `platforms;android-35` and `build-tools;34.0.0` (the default build tools of AGP 8.7.3; the first build downloads them if they are missing and the SDK licences are accepted); `sdk.dir` in `android/local.properties` |
| `android/local.properties` | Keys: `sdk.dir`, optional `tapvault.sponsor.account` and `tapvault.sponsor.key`, optional `tapvault.mainnet=true` (shows Mainnet in Settings → Network; leave it out until the gates in docs/WALLET-FEATURES-PLAN.md 0.4 pass), optional `tapvault.oneclick.jwt` (NEAR Intents integrator JWT; without it 1Click adds its own fee) and `tapvault.ramp.key` (Ramp Network's public host key). Gitignored. Never commit it, never print its values |
| Rust | rustup; `rust-toolchain.toml` installs 1.86.0 with `rustfmt`, `clippy` and the `wasm32-unknown-unknown` target |
| cargo-near 0.17.0 | Only to rebuild the wasm. The app builds without Rust because the wasm is committed |
| Python 3 | Only for `tools/`; `gen_emv_vectors.py` needs the `cryptography` package |
| adb, a phone | Android 9+ with NFC, a screen lock (Android 9/10: also a fingerprint) |
| Network | Testnet RPCs, the faucet, and the sandbox node download |

Sponsor (optional): when `tapvault.sponsor.account` and `tapvault.sponsor.key` (a full-access key, `ed25519:…`) are both set, the APK creates sub-accounts of that account when the faucet refuses: wallet 5.5 NEAR, fee account 0.5 NEAR. A key that doesn't parse is ignored when the app runs: it logs "sponsor key in this build is unreadable; using the faucet only" and uses the faucet alone. The key is compiled into `BuildConfig`, so anyone with that APK can spend the sponsor's testnet NEAR. Leave both lines out to build without it.

Installing over an existing install needs the same signing key. Debug keys differ per computer; Android then refuses the update until you uninstall, and uninstalling deletes the phone key (the wallet on that phone is lost). Keep building test APKs on one machine.

## Invariants

Don't break these. Most have a test or a build check behind them.

1. **Pinned toolchain.** rustc 1.86.0 (`rust-toolchain.toml`), cargo-near 0.17.0, near-sdk `~5.14`, near-workspaces 0.20 with sandbox nearcore 2.6.5 (`sandbox_with_version("2.6.5")` in `contract/tests/sandbox.rs`), and `incompatible-rust-versions = "fallback"` in `.cargo/config.toml` so dependencies resolve for 1.86. Newer combinations broke the build or the sandbox tests, and any toolchain change changes the wasm bytes. Android side: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0, JDK 17.
2. **Kotlin bytes == Rust bytes.** Everything signed or Borsh-encoded exists twice: `contract/src/types.rs` ↔ `Model.kt` + `Borsh.kt`, `crates/tapvault-core/src/domain.rs` ↔ `Domain.kt`. Change both, run `UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors`, then `./gradlew :core:test` (`VectorsTest`), and commit `vectors.json` in the same change. Also ported by hand: `emv::verify_sdad` ↔ `Rsa.verifySdad`, `LimitState::is_instant` ↔ `LimitInfo.isInstant`.
3. **One shipped wasm, one hash, three places.** `contract/res/tapvault_contract.wasm` is what every new wallet runs. Its SHA-256 is pinned in `android/app/build.gradle.kts` (`contractWasmSha256`; `verifyWasm` refuses anything else), in `README.md` ("Contract build": hex, base58, size) and in `contract/res/README.md` (hex, base58, size). The build isn't reproducible: rebuilding elsewhere gives a different hash, so don't replace the file unless the contract changed.
4. **Existing wallets never get new code.** There is no upgrade path; each wallet keeps the code it was deployed with. Wallets created before 12 Nov run `GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow`, which rejects Visa online fDDA signatures.
5. **The PAN, track data, cardholder name and certificates never leave the card session.** `EmvSession` holds them in memory only; `CardReport` carries the last four digits; the trace prints values only for tags in `TRACE_VALUES` (plus application labels and short tag-`80` answers), lengths for everything else (`EmvSession.show`). Never log, store, share or send them. Test: `EmvTest.reportsAndTracesNeverCarryTheCardNumber`.
6. **No simulated card in the app.** `SimCard`/`SimKeys` live in `android/core/src/test`; the Rust `TestCard` only exists with feature `test-card` (a dev-dependency). The manifest requires NFC. Don't add a demo mode.
7. **One network per wallet; mainnet off.** Every wallet has one network, stored with it (`WalletIndex.networkOf`; entries from before networks are testnet), and always talks to that network's `NearClient`, whatever Settings → Network shows. Network literals live only in `Network.kt`. Sponsor and faucet are testnet only (`Onboarding.createAccounts` refuses both elsewhere; `NetworkTest`). Mainnet stays off (`BuildConfig.MAINNET_ENABLED`, from `tapvault.mainnet`) until real-card testing and a contract review; don't turn it on in a shared build, and don't send real NEAR to a wallet before then.
8. **Secrets never in git.** `android/local.properties`, `~/.near-credentials`, `.env` and any `ed25519:` secret stay out of code, tests, docs, logs and commit messages.
9. **Never run two Gradle builds of the same project at once.** A concurrent run once made a test task report a false failure.
10. **The phone signs what it shows.** Build the action on the phone, compute the hash locally (`Domain.intentHash`) and set `KeystoreSigner.reason` from the same values; don't sign hashes taken from RPC views (`contract/src/views.rs`: clients compute what they sign). `TapvaultWallet.approvePhone` and `cancelByPhone` take a proposal from the RPC, so they parse its action (`ActionJson.parse`), recompute the intent hash and refuse a mismatch (`ProposalInfo.verifiedAction`), and set the prompt from the parsed action (`beforeSign`). Known exception on v0.1 wallets: the card's challenge comes from `get_proposal` (`card_challenge`; that view doesn't return the seed). v0.2 wallets return `card_seed`, and `TapvaultWallet.checkedChallenge` (called by `awaitCardChallenge`) recomputes the challenge from the parsed action and refuses a mismatch. See [docs/DESIGN.md](docs/DESIGN.md), known gaps.
11. **A card alone never moves funds.** Every transfer needs a P-256 approval (`policy::check_execution`, `Refusal::NeedsUserVerifiedApproval`); card approvals only count against a challenge sealed after the proposal (`seal_challenge`). Read [docs/DESIGN.md](docs/DESIGN.md) before touching `proposals.rs`, `guard.rs` or `policy.rs`.
12. **Threshold 1 only for a phone-only wallet.** Contract v0.3 allows threshold 1 only while the wallet's one signer is the phone (`policy::threshold_is_valid`, checked in `validate_config`). Adding any key to such a wallet must raise the threshold to 2 in the same change (`AddSigner` + `SetThreshold(2)`, `AppViewModel.addFirstCard`). The app treats a wallet as phone-only by `WalletState.phoneOnly` (threshold 1, no cards), never by a local flag.

## Making a change safely

| You changed | Run before committing |
|---|---|
| `crates/tapvault-core` | `cargo test` |
| `contract/src` | `cargo test`, then the sandbox test; to ship it, follow [Shipping a contract change](#shipping-a-contract-change) |
| Signed bytes or argument types (`types.rs`, `domain.rs`, `Model.kt`, `Domain.kt`, `Borsh.kt`) | `UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors`, `cargo test`, `./gradlew :core:test` |
| Card code (`Emv.kt`, `Tlv.kt`) | `./gradlew :core:test`, then `TestnetE2ETest` with all three `TAPVAULT_CARD_PROFILE` values; on a real card, Card check before and after |
| `capk.txt` | Verify the key's published SHA-1 checksum yourself (the app doesn't check checksums at load time); `./gradlew :core:test` (`EmvTest.capkFileLoadsWithChecksummedKeys`) |
| `NearClient.kt`, `Wallet.kt` | `./gradlew :core:test`, `RpcProbeTest` and `TestnetE2ETest` live |
| `android/app` | `android/build-apk.sh`, install, walk the relevant part of [docs/TESTING.md](docs/TESTING.md) |
| User-visible text | Same as `android/app`; keep [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) in step with the strings |

### Shipping a contract change

1. `cargo test` and the sandbox test pass.
2. `cd contract && cargo near build non-reproducible-wasm`, then `cp ../target/near/tapvault_contract/tapvault_contract.wasm res/`.
3. `shasum -a 256 res/tapvault_contract.wasm` for the hex, and the Python snippet under [Checking the shipped build](#checking-the-shipped-build-and-a-wallet-on-chain) with `res/tapvault_contract.wasm` as its argument for the base58. Put the hex in `contractWasmSha256` (and the base58 in the comment above it) in `android/app/build.gradle.kts`, and hex, base58 and size in `README.md` ("Contract build") and in `contract/res/README.md`. Then `grep -rn` the old base58 and hex across the repository: several docs quote them.
4. `android/build-apk.sh` (the hash check passes).
5. `TestnetE2ETest` with all three card profiles (it deploys `contract/res/tapvault_contract.wasm`, now the new build).
6. Note in [docs/STATUS.md](docs/STATUS.md) that wallets created before this build keep the old code.

## Commit style

- Small, focused commits: one change and its tests per commit.
- Subject: `<area>: <imperative summary>`, area in lowercase (except `README`), e.g. `capk: add RuPay 6C and 6D; say plainly when a card's CA key is unknown`, `android core: fall back across testnet RPCs, never re-sign a sent transaction`. Areas in use: `contract`, `core`, `emv`, `capk`, `tests`, `android`, `android core`, `android app`, `onboarding`, `docs`, `README`.
- No trailers (no `Co-authored-by:`, no `Signed-off-by:`).
- Commit `vectors.json` with the change that alters signed bytes, and the wasm with all three hash updates.
- Never commit `android/local.properties`, keys, `target/`, `android/*/build/` or `android/app/src/main/assets/tapvault_contract.wasm` (generated by the build).

## Where things are

| Task | File and symbol |
|---|---|
| What a card is asked to do (methods and their order) | `Emv.kt`: `EmvSession.plans`, `EmvSession.attempt`, `fdda`, `dda`, `cda` |
| Enrol a card / sign with the enrolled card | `Emv.kt`: `CardService.enrol`, `CardService.sign`, `CardService.fallbacks` |
| Terminal values sent to the card (TTQ, amount, currency, date) | `Emv.kt`: `EmvSession.terminalValue`, `TTQ_ONLINE`, `TTQ_OFFLINE_OK` |
| Certificate chain and CA lookup | `Emv.kt`: `EmvSession.recoverCardKey`, `issuerKeyFrom`, `CapkStore`; `resources/capk.txt` |
| The card report and trace | `Emv.kt`: `CardReport.text`, `CardReport.diagnostics`, `EmvSession.report`, `show`, `TRACE_VALUES` |
| How a card signed at enrolment | `Emv.kt`: `CardRecipe` (encode/decode); stored per card in `Store.cards` (`CardBook.kt`: `CardBook`, `KnownCard`, keyed by card key hash; `CardBook.load` migrates the old single-card keys) |
| Which card was tapped, hold, then sign | `Emv.kt`: `CardService.identify` (match by key hash before anything is signed, `WrongCardException`), `CardService.HeldCard` (`stillThere`, `sign`), `CardCandidate`; `AppViewModel.tapWalletCard`, `holdCard`, `setHoldTimer`; `Components.kt`: `HoldPanel` |
| Add backup card | `AppViewModel.readBackupCard` (enrol + `AlreadySignerException` + `provesPossession` in `Emv.kt`), `addBackupCard`, `BackupCandidate`, `pendingCardAdds`; `Security.kt`: `AddBackupIntro`, `BackupReview` |
| Security Center, Home status pill | `Security.kt`: `SecurityScreen` (protection score, keys, protection, checks), `HoldTimerSheet`; `Screens.kt`: `securityPill` |
| Lost card (remove a card) | `AppViewModel.removeCard` (optional freeze first; `RemoveSigner` + `Unfreeze` when frozen); `Security.kt`: `LostCardSheet` |
| Integrity check | `Integrity.kt`: `Integrity.evaluate`, `Report`, `codeHashOf`, `PREVIOUS_BUILD`; `NearClient.codeHash`, `accessKeys`; `AppViewModel.integrityNow` (every 6 h in `refresh`), `checkIntegrity`, `shippedCodeHash` (from the APK's own wasm asset, so no extra pinned hash) |
| Watchtower alerts | `Watch.kt`: `Watch.check`, `memoryOf`, `Memory`; `Watchtower.kt`: `Watchtower.schedule`, `runOnce`, `WatchJob` (JobScheduler, ~15 min); `AppViewModel.setAlerts`, `openFromAlert`; `Store.watchMemory`, `WalletIndex.alertsOn` |
| Send review warnings | `Screens.kt`: `Send` (review sheet: bold last 6, first time, look-alike, pasted, over half the balance, Arrives, left today); `Receivers.kt`; `Store.receivers`, `AppViewModel.rememberReceiver` |
| App lock | `AppViewModel.lockIfAway`, `wentToBackground`, `unlock`; `Watchtower.kt`: `AppLock`; `Screens.kt`: `LockOverlay`; `WalletIndex.backgroundAt` |
| Backup card during setup | `AppViewModel.continueSetup` (second `Onboarding.addCard` with the signer count 2), `setSetupOptions`, `skipBackupCard`; `Store.wantBackup`, `pendingCard2`; `Onboarding.kt`: `LimitsScreen` (Extra protection), `SetupProgress` |
| Recover on a new phone | `Recovery.kt`: `Recovery.plan`, `isRecovery`; `Wallet.kt`: `TapvaultWallet.proposeWithCard`, `Onboarding.createFeeAccount`; `AppViewModel.findWalletToRecover`, `startRecovery`, `approveRecovery`, `refreshRecovery`, `finishRecovery`, `myRecovery`; `Join.kt`: `RecoverScreen`; `Store.joinMode` |
| Guardian phone | `Guardian.kt`: `Guardian.offer`, `decode` (checksum, curve, possession signature via `P256.verify`), `addAction`, `WARNING`; `AppViewModel.becomeGuardian`, `checkGuardianAdded`, `addGuardian`; `Join.kt`: `GuardianScreen`, `AddGuardianSheet` |
| Fingerprint only (strict key) | `Keystore.kt`: `KeystoreSigner.create(strict)`, `isStrict`; `AppViewModel.switchToStrictKey`, `adoptPendingKey` (in `refresh`); `Store.strictKey`, `phoneAlias`, `pendingAlias`; `WalletIndex.aliasOf`; `Join.kt`: `StrictKeySheet` |
| Wallet without a card (contract v0.3) | `crates/tapvault-core/src/policy.rs`: `threshold_is_valid`, `PHONE_ONLY_THRESHOLD`; `contract/src/lib.rs`: `validate_config`. Client: `Wallet.kt`: `WalletState.phoneOnly`. App: `Onboarding.kt`: `CreateChoiceSheet` ("Create wallet"), `LimitsScreen` (Bank card Now/Later); `AppViewModel.chooseSetup`, `continueSetup` (threshold from the signers on chain), `continueWithoutCard`, `sendPhoneOnly`, `addFirstCard` (via `addBackupCard`), `requestUnfreeze`; `Store.withCard`; `Screens.kt`: Home "Add your card" notice, `securityPill`; `Security.kt`: "No card yet" |
| Contract v0.2 (possession, seed, allowlist, upgrade) | `contract/src/proposals.rs`: `propose(…, possession)`, `prove_possession`, `record_possession`; `execute.rs`: `execute_upgrade`, allowlist check; `lib.rs`: `migrate`, `CONTRACT_VERSION`; `types.rs`: `PossessionProof`, `OpPossession`, `ConfigOp::{AllowReceiver, DisallowReceiver, UpgradeCode}`; `domain.rs`: `possession_message`, `card_possession_challenge`; `policy.rs`: `ActionClass::Upgrade`. Client: `Wallet.kt`: `isV02`, `contractVersion`, `provePossession`, `executeUpgrade`, `checkedChallenge`; `Model.kt`: `PossessionProof`; `Domain.kt`: `possessionMessage`, `cardPossessionChallenge`; `NearClient.kt`: `NearAction.RawFunctionCall`. App: `AppViewModel.isV02`, `changeAllowlist`; `Join.kt`: `AllowlistSheet` (Security Center → Instant receivers, v0.2 wallets only) |
| Security key (app-enforced) | `SecurityKey.kt` (core: PBKDF2 hash, Keystore HMAC pepper, pauses after wrong tries; `SecurityKeyTest`); `Keystore.kt`: `SecurityPepper`; `AppViewModel.requireSecurityKey` (asked in `send` after the card tap, approvals, add/remove card, guardian, allowlist, strict key, unfreeze; never for freeze or cancel), `createSecurityKey` (first setup step), `changeSecurityKey`, `resetSecurityKey` (card tap verified with `provesPossession` + fingerprint); `KeyAndContacts.kt`: `SecurityKeySheet`, `SecurityKeySettingsSheet`; `Store.securityKey`, `keyFailures` |
| Address book | `AddressBook.kt` (core); `WalletIndex.addressBook` (shared by all wallets); `AppViewModel.saveContact`, `deleteContact`; `KeyAndContacts.kt`: `AddressBookSheet`, `ContactChips` (Send) |
| Approve / cancel a proposal from the RPC (gap 6b) | `Wallet.kt`: `ProposalInfo.verifiedAction`, `TapvaultWallet.approvePhone`, `cancelByPhone` (`beforeSign`); `Model.kt`: `ActionJson.parse`, `Action.describe` |
| SDAD check in the contract | `crates/tapvault-core/src/emv.rs`: `verify_sdad`, `verify_card_challenge`, `EmvKeyLayout::validate` |
| Signed messages | `crates/tapvault-core/src/domain.rs` and `Domain.kt` |
| Limits, delays, approval rules | `crates/tapvault-core/src/policy.rs`: `check_execution`, `LimitState`, `Delays`, `rate_limit`; `contract/src/execute.rs`: `gate` |
| Contract constants | `contract/src/lib.rs`: `CARD_APPROVAL_WINDOW`, `MAX_OPEN_PER_SIGNER`, `UNAPPROVED_TTL`; `types.rs`: `MAX_SIGNERS`, `MAX_LIMITS`, `MAX_CONFIG_OPS`; `crates/tapvault-core/src/policy.rs`: `EXECUTION_WINDOW`, `MIN_TRANSFER_DELAY`, `MIN_CONFIG_DELAY`, `MIN_RECOVERY_DELAY`, `MAX_DELAY`, `MIN_THRESHOLD`, `MAX_PROPOSALS_PER_DAY`; `emv.rs`: modulus, UN and terminal-data bounds |
| Contract errors | `contract/src/error.rs` |
| Setup flow (app) | `AppViewModel.continueSetup`, `beginSetup`, `startOver`; `Store.setupStep`, `setupPlan`, `pendingCard`; `PendingCard` |
| Account creation and funding | `Wallet.kt`: `Onboarding.createAccounts`, `Onboarding.Plan`, `Onboarding.Sponsor` (`WALLET_FUNDING`, `GAS_FUNDING`); `AppViewModel.sponsor`, `hasSponsor` |
| Deploy, add card, finalize | `Wallet.kt`: `Onboarding.deployAndInit`, `addCard`, `finalize`; `contract/src/setup.rs` |
| Limits and delays the app sets | `AppViewModel.continueSetup` (`FinalizeArgs`, threshold 2), `Model.kt`: `Delays.SHORTEST` |
| Send | `AppViewModel.send`, `approveAndMaybeExecute` (sends the card approval alone if the contract refuses the execute); `TapvaultWallet.proposeByPhone`, `awaitCardChallenge`, `approveCardAndExecute`, `approveCard` |
| Activity actions | `AppViewModel.approveWithCard`, `approveWithPhone`, `execute`, `cancel`, `closeStale`; `Screens.kt`: `ActivityScreen`, `proposalStatus`, `secondsToExecute`, `delayFor`, `expired`, `closable` |
| Freeze and unfreeze | `AppViewModel.freezeWithCard`, `freezeWithPhone`, `requestUnfreeze`; `contract/src/guard.rs`: `freeze`; `contract/src/execute.rs`: `apply_config` (`ConfigOp::Unfreeze`) |
| Tokens (list, add, hide, enable) | `Tokens.kt` (core): `KnownTokens`, `TokenPrefs` (`isShown`), `Tokens.load`, `metadata`, `isRegistered`, `register` (fee account pays `storage_deposit`), `held` (FastNEAR); `TokenAmount`; `Store.tokenPrefs`; `AppViewModel.tokens`, `refreshTokens` (≤ once a minute, forced on pull), `showToken`, `hideToken`, `enableToken`; `TokensUi.kt`: `TokensSection` (Home), `ManageTokensSheet`, `TokenSheet` |
| Send a token, token instant limits | `AppViewModel.send(receiver, amount, token, label)`, `checkCanPay` (balance, receiver registered on the token), `pay` (seed / phone-only / card paths for any `Asset`), `setTokenLimit`, `removeTokenLimit` (`SetLimit` / `RemoveLimit`, at most `MAX_LIMITS`), `SendDraft`/`openSend`; `Wallet.kt`: `WalletState.limitFor`, `isInstantTo(asset, …)`, `canAddLimit`, `ProposalInfo.transferAsset`; `Tokens.transferFrom` (seed wallets); `TokenAmount.describe` (prompts and Activity); `Screens.kt`: `Send` (asset picker); `TokensUi.kt`: `LimitEditor` |
| Swap, bridge, prices (NEAR Intents, mainnet only) | `Intents.kt` (core): `OneClick` (`tokens`, `quote`, `submitDeposit`, `status`), `QuoteAsk`, `SwapQuote`, `SwapState`, `Prices` (verified tokens only); `Swap.kt`: `Swap.check` (the wet quote must match what was asked before anything is signed), `ChainAddress` (address formats per chain), `SwapRecord`, `Ramp`; `Store.swaps`; `AppViewModel.tradeAvailable`, `refreshPrices`, `previewSwap`, `swapIsInstant`, `swap`, `bridgeIn`, `refreshSwaps`, `rampUrl`; `Trade.kt`: `TradeScreen` (Swap, Bridge, Buy, Sell, Recent) |
| Networks (Settings → Network) | `Network.kt` (`TESTNET`, `MAINNET`, `accountProblem`, `isImplicit`); `Store.kt`: `WalletIndex.network` (the switch), `networkOf`, `idsOn`, `addressBook(n)`, `MAINNET_ENABLED`; `AppViewModel.network`, `nearFor`, `switchNetwork`, `requireFaucetForJoin`; `SeedWallet.checkNotOnOtherNetwork` (refuses protecting an implicit account that runs a contract on the other network); `NetworkUi.kt`: `NetworkSheet`, `NetworkFrame` (testnet strip, only when the build has mainnet); `Watchtower.runOnce` (each wallet on its own network) |
| RPC endpoints, retries, sending | `NearClient.kt`: `Network.rpcUrls`, `RETRYABLE_CAUSES`, `rpc`, `send`, `awaitOutcome`, `readableError` |
| Explorer links | `NearClient.explorerTx`, `NearClient.explorerAccount` |
| Phone key and the prompt | `Keystore.kt`: `KeystoreSigner.create`, `publicKey`, `sign`, `reason`, `hardwareLabel`; `AppViewModel.phoneIndex` (the phone's signer index, or a readable error) |
| NFC reader and tap handling | `MainActivity.onResume` (`enableReaderMode`), `IsoDepTransceiver`; `AppViewModel.withCard`, `onTag`, `awaitTag` |
| Screens and UI text | `Screens.kt` (`Home`, `Send`, `ActivityScreen`, `CardCheck` ("Your card" after setup), `SettingsScreen` with the technical log), `Onboarding.kt` (`Welcome`, `LimitsScreen`, `SetupProgress`), `Components.kt` (`TapSheet`, `OutcomeBanner`, `BusyOverlay`, `SlideToConfirm`), `Glass.kt` (`GlassTabBar`, `AppBackground`, `frosted`), messages in `AppViewModel.kt`; prompt errors in `Keystore.kt`, network errors in `NearClient.kt` (`readableError`, `awaitOutcome`), card errors in `Emv.kt` (exception messages, `swMeaning`) |
| Local storage | `Store.kt` |
| Shipped contract and its hash | `contract/res/`, `android/app/build.gradle.kts` (`contractWasmSha256`, `verifyWasm`, `copyWasm`) |
| Sponsor build config | `android/app/build.gradle.kts` (`buildConfigField` `SPONSOR_ACCOUNT`, `SPONSOR_KEY`) |
| Simulated card for tests | `android/core/src/test/.../SimCard.kt` (`Profile`), `SimKeys.kt`; Rust `crates/tapvault-core/src/testcard.rs` |
| Live testnet test | `android/core/src/test/.../TestnetE2ETest.kt`; sponsor env forwarding and the `TAPVAULT_WASM` default (`contract/res`) in `android/core/build.gradle.kts` (`tasks.test`) |
