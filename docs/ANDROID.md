# Android app

The Tapvault Android app: how it is put together, every screen, what each
view-model flow does step by step, threads, NFC, the phone key, local storage,
build configuration, building, installing, logs and limitations.

The code is the source of truth. Symbols are named so you can search for them.
How the app talks to a bank card is in [CARDS.md](CARDS.md); the flows from the
chain's point of view (sequence diagrams) and the NEAR client are in
[ARCHITECTURE.md](ARCHITECTURE.md); every user-visible error is in
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

> **Status, 12 Nov 2026.** The app builds, and its client code (`:core`)
> passes a live testnet run with a simulated card and a software phone key.
> The app has run only on the emulator. **Nothing has run on a real phone**:
> not the Keystore key, the fingerprint prompt, NFC reader mode, or a real
> card. Details: [STATUS.md](STATUS.md).

## At a glance

| | |
|---|---|
| Package | `app.tapvault`, versionCode 1, versionName `0.1.0` |
| Android | minSdk 28 (Android 9), targetSdk 35, compileSdk 35 |
| Needs | NFC hardware (declared required), NFC switched on, a screen lock; Android 9 and 10 also an enrolled fingerprint |
| Network | NEAR testnet only; no switch for mainnet |
| UI | One activity (`MainActivity`), Jetpack Compose, Material 3, dark theme only, portrait only, English only |
| Build | Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.1.0, JDK 17 |
| Output | `android/app/build/outputs/apk/debug/app-debug.apk`, signed with the build machine's debug key |

## What is tested

| Area | How | Result |
|---|---|---|
| `:core` client: NEAR transactions, contract calls, EMV card protocol | JVM tests (`EmvTest`, `VectorsTest`), and `TestnetE2ETest` on live testnet with `SimCard` and `SoftP256Signer` standing in for the card and the phone key | Pass (simulated card, software key) |
| App on the emulator | 11 Nov, with the old in-app demo card: setup, send, approvals, freeze, card check. 12 Nov: the current build launches and refuses setup cleanly on a device without NFC | Pass (no NFC on the emulator) |
| Keystore key in the TEE, BiometricPrompt, waiting for the foreground | none | Untested on a device |
| NFC reader mode, `IsoDep` with a real card, retries, wrong-card handling | none | Untested on a device |
| Android 9 / 10 fingerprint-only path | none | Untested |

There are no on-device (instrumented) tests. Test instructions:
[TESTING.md](TESTING.md).

## Module layout

`android/` is one Gradle project (`settings.gradle.kts`: root `tapvault`,
modules `:core` and `:app`).

### `:core`: plain Kotlin library (JVM)

No Android dependencies, so it runs and is tested on a desktop JVM. Package
`app.tapvault.core`, in `android/core/src/main/kotlin/app/tapvault/core/`:

| File | What it holds |
|---|---|
| `Emv.kt` | Card protocol: `EmvSession`, `CardService` (`enrol`, `sign`, `inspect`), `CardRecipe`, `CardReport`, `CapkStore`, `Rsa`, `WrongCardException`, `CardCheckFailed`. See [CARDS.md](CARDS.md) |
| `Tlv.kt` | BER-TLV parser and Data Object Lists |
| `NearClient.kt` | NEAR JSON-RPC client (`NearClient`), ed25519 keys (`NearKey`), transaction actions (`NearAction`), RPC fallback, faucet and sub-account creation |
| `Wallet.kt` | `TapvaultWallet` (one wallet's views and calls), `Onboarding` (accounts, deploy + `new`, add card, finalize), `WalletState`, `ProposalInfo`, `LimitInfo` |
| `Model.kt` | Kotlin mirrors of the contract's types with their Borsh and JSON forms (`SignerKey`, `Action`, `ConfigOp`, `FinalizeArgs`, `CardSig`, `SignerProof`, `Delays`) |
| `Domain.kt` | Every message a signer signs (`Domain.*`), mirrored from `crates/tapvault-core/src/domain.rs` |
| `P256.kt` | `P256Signer` interface, key compression, DER to raw signatures, `SoftP256Signer` (tests) |
| `Bytes.kt` | hex, base64, base58, SHA-1, SHA-256, little-endian helpers, `Near.parse` / `Near.format` (1 NEAR = 10^24 yocto) |
| `Borsh.kt` | Minimal Borsh encoder |
| `resources/capk.txt` | EMV CA public keys |

Dependencies: `org.bouncycastle:bcprov-jdk18on:1.79` (ed25519),
`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`. `jvmToolchain(17)`.

### `:app`: the Android application

`android/app/src/main/kotlin/app/tapvault/`:

| File | What it holds |
|---|---|
| `MainActivity.kt` | The only activity: NFC reader mode on while resumed, `IsoDepTransceiver` (the APDU link), `vibrate()` |
| `AppViewModel.kt` | All app state and every flow (`AndroidViewModel`); `Screen`, `TapRequest`, `Outcome` |
| `Keystore.kt` | `KeystoreSigner`: the phone's P-256 key behind BiometricPrompt |
| `Store.kt` | `Store`: one wallet's settings in SharedPreferences; `WalletIndex`: the wallets on this phone |
| `PendingCard.kt` | A card read during setup, kept until it is added on-chain |
| `Onboarding.kt` | Welcome (three-slide intro), `LimitsScreen` (spending limits, the step before setup), `SetupProgress`, `NfcNotice`, the onboarding illustrations |
| `Screens.kt` | `TapvaultApp` (shell, transitions, tab bar, overlays), `Home`, `ReceiveSheet`, `Send` (keypad and review sheet), `ActivityScreen` (`ProposalCard` with its approval tracker), `CardCheck` ("Your card" after setup), `CardReportCard`, `SettingsScreen` (with the technical log sheet), `WalletPicker` |
| `Components.kt` | UI parts: `TvButton`, `ActionButton`, `Group`/`Row_` lists, `Notice`, `StatusPill`, `TvField`, `AmountField`, `CountingAmount`, `OutcomeBanner`, `AnimatedCheck`, `BusyOverlay`, `GlassDialog`/`ConfirmDialog`, `GlassSheet`, `TapSheet`, `CardArt`, `SlideToConfirm`, `QrCode`, `LogoMark` |
| `Glass.kt` | The glass look and motion: `glassRim`, `frosted` (backdrop blur through Haze, `LocalHaze`), `AppBackground` (ambient light and grain), `GlassTabBar` and its `LiquidLens` (AGSL, Android 13+), `enter` (staggered entrance), `sharedCard` (the card flying between Home and Your card), `rememberTilt` (rotation sensor), `rememberReduceMotion` |
| `Theme.kt` | Colour tokens `Tv` (titanium monochrome, `Tv.titanium()` brush), type scale `Type` (Inter), `TapvaultTheme` (dark only), `Modifier.surface` (static glass), icons `Ic` / `TvIcon` |

Resources: `res/values/themes.xml` (`Theme.Tapvault`, parent
`Theme.AppCompat.NoActionBar`) and `res/values-v31/themes.xml` (adds the
Android 12+ launch screen: `@drawable/ic_splash` on the app background);
`res/font/inter_*.ttf` (Inter 4.1, licence in
`android/app/FONT-LICENSE-Inter.txt`); line icons `res/drawable/ic_*.xml`
converted from Lucide (licence in `android/app/ICONS-LICENSE-Lucide.txt`); the
adaptive launcher icon (`ic_launcher_background`, `ic_launcher_foreground`,
`ic_launcher_monochrome`). The asset
`src/main/assets/tapvault_contract.wasm` is copied in by the build (see
[The contract wasm](#the-contract-wasm)) and is gitignored.

Dependencies: Compose BOM `2024.12.01` (ui, material3, material-icons-core),
`activity-compose` 1.9.3, `lifecycle-viewmodel-compose` 2.8.7, `biometric`
1.1.0, `fragment-ktx` 1.8.5, `kotlinx-coroutines-android` 1.9.0, ZXing `core`
3.5.3 (the Receive QR code), Haze 1.5.4 (`dev.chrisbanes.haze`, backdrop blur), and `:core`.

### Tests

`android/core/src/test/kotlin/app/tapvault/core/`: `EmvTest` (15 tests),
`VectorsTest` (3; Kotlin bytes equal Rust bytes, using
`src/test/resources/vectors.json`), `RpcProbeTest` (1) and `TestnetE2ETest`
(1), the last two only with `TAPVAULT_TESTNET=1`. `SimCard.kt` and
`SimKeys.kt` are the simulated card, test-only. How to run them:
[TESTING.md](TESTING.md).

## Screens

The look: titanium monochrome. Near-black background with two slow light
pools and fine grain (`AppBackground`), glass surfaces (translucent white with
a rim that catches light at the top-left, `Modifier.surface`), platinum
primary buttons with black text, and a brushed-titanium brush (`Tv.titanium()`)
on the balance and the card. Colour carries meaning only: green
(`Tv.Positive`) for approved and done, amber for waiting, ice blue for frozen,
coral for danger. Floating parts (tab bar, status-bar strip, result banner,
busy overlay, tap sheet) blur what scrolls behind them through Haze on
Android 12+, and fall back to a dark tint below that. Bottom sheets and dialogs
are separate windows that can't blur the app, so they use an opaque dark glass
(`Tv.Sheet`); dialogs ask the system to blur behind them on Android 12+. Inter
throughout, with tabular figures for amounts. The app is dark only:
`TapvaultTheme` reports night mode to everything inside it so system-drawn
parts (bottom-sheet windows and their navigation bar) match.

Motion: screens slide and scale with springs; list items rise in one after
another (`enter`); the card leans with the phone (`rememberTilt`, the game
rotation sensor, registered only while the app is resumed) and flies between
Home and Your card (`sharedCard`); the balance counts up when it changes. All
of it is skipped when the system's animator duration scale is 0
(`rememberReduceMotion`).

### Navigation

- `Screen` enum: `Welcome`, `Limits`, `Setup`, `Home`, `Send`, `Activity`,
  `CardCheck`, `Settings`. Everything is drawn by `TapvaultApp` in
  `Screens.kt`; the keyboard closes on every screen change.
- Start screen: `Home` when `Store.setupStep` is 4, `Setup` when it is 1 to 3,
  `Welcome` when it is 0.
- The tab bar (`GlassTabBar`) floats above the navigation bar on Home,
  Activity and Settings once setup is done: three icons ("Wallet", "Activity",
  "Settings") in a frosted pill, the current one under a glass bubble that
  slides with a spring, stretches with its speed and shows the label. Dragging
  along the bar moves the bubble too. On Android 13+ the bubble magnifies what
  is under it (`LiquidLens`, an AGSL shader). "Activity" carries an amber count
  badge while proposals are open.
- Card check is not a tab: after setup it is "Your card", opened by tapping
  the card on Home or from Settings › Your card. Its back button and system
  back return to the tab it was opened from (`AppViewModel.cameFrom`).
- System back on Limits goes to Welcome; on Send, Activity and Settings it
  goes to Home (to Welcome before setup is done). On Home, Welcome and Setup it
  leaves the app.
- Home and Activity refresh on pull-down (`AppViewModel.pullRefresh`) as well
  as every 8 s.
- `AppViewModel.go(screen)` changes screen, clears the result banner and, when
  leaving a tab, remembers it in `cameFrom`.

### Welcome

- Wordmark and a "Testnet" pill. A three-slide pager (auto-advances every
  5.5 s until swiped), each with an animated illustration: "Your bank card is
  your hardware wallet", "Two keys, every payment", "Limits that have your
  back". Swiping moves the picture and the text at different speeds; the
  buttons sit on a glass panel.
- `NfcNotice`: "This phone has no NFC" / "It can't read a bank card. Use an
  Android phone with NFC.", or "NFC is off" / "Turn it on to tap your card
  (Connected devices → NFC)." with "Open NFC settings".
- "Create a new wallet" opens Limits. "Check my card first" opens Card check.
  With other wallets on the phone: "Back to my wallets" (`cancelNewWallet`).
- Footer: "Self-custody on NEAR testnet. Only test NEAR, no real money."

### Limits (`LimitsScreen`)

- "Set your spending limits", then two cards: "Instant payment limit" and
  "Daily limit", each a NEAR field prefilled from `Store` (defaults 1 and 2; a
  comma becomes a dot, anything but digits and dots is dropped) with preset
  chips (0.5 / 1 / 5 and 2 / 5 / 20 NEAR). A daily limit below the instant
  limit shows "The daily limit must be at least the instant limit."
- "What you'll need": the card, a screen lock, about a minute.
- "Continue" stays pinned at the bottom; it is disabled without NFC or while
  the limits are invalid, and calls `beginSetup`.

### Setup (`SetupProgress`)

- Title "Creating <wallet name>", a progress ring "<done>/6 steps", headline
  "Setting up your wallet" (while running), "Ready to set up", "Paused" or
  "All set".
- A vertical stepper, in code order; the running step shows a spinner and the
  current busy label under it (setup has no busy overlay):

  | Step | Done when |
  |---|---|
  | "Phone key in secure hardware" | `AppViewModel.phoneKeyExists` (this wallet's alias) |
  | "Wallet account on NEAR" | step ≥ 1 |
  | "Card read and signed" | a `PendingCard` is saved, or step ≥ 3 |
  | "Wallet contract deployed" | step ≥ 2 |
  | "Card added as a signer" | step ≥ 3 |
  | "Limits set, account locked" | step ≥ 4 |

- "Account: <account id>" once the accounts exist.
- The last card report in memory (`AppViewModel.cardReport`), if any, and
  "Share card report" (`CardReport.diagnostics()` as text; see
  [CARDS.md](CARDS.md#where-it-comes-from)). `beginSetup` clears any earlier
  report, such as a Card check's from Welcome.
- While idle and unfinished: "Continue setup" (`continueSetup`), "Switch
  wallet (this setup stays saved)" when there are other wallets, and "Start
  over", which asks "Start over?" with, when the build has no usable sponsor
  (`AppViewModel.hasSponsor`), "The phone key and this setup's saved state are
  removed. The accounts made so far stay on testnet with their test NEAR.";
  with a sponsor, "The accounts made for this setup are deleted (their NEAR
  goes back to the sponsor) and the phone key is removed." (step 0 or 1) or
  "The half-built wallet is left on testnet with its test NEAR. The fee
  account is deleted and the phone key removed." (step 2 or 3); buttons "Start
  over" and "Keep going".

### Home

Only what is used every day; the details moved to Settings.

- Header: avatar, wallet name and account (tap: the wallet switcher), and a
  QR button (Receive).
- Offline notice when the last refresh failed: "Offline" / "Can't reach NEAR
  testnet right now. Showing the last known state; retrying."
- The card (`CardArt`): brushed titanium with the chip, "tapvault", the
  contactless mark, "•••• <last 4>" and the network once known. It leans with
  the phone and frosts over with a snowflake while the wallet is frozen. Tap it
  for Your card.
- "Total balance": the spendable balance (balance minus the part locked for
  storage), up to 4 decimals, counting up when it changes, and "NEAR"; a
  shimmer until loaded. Pill "Frozen", or "<x> NEAR available instantly"
  (`LimitInfo.availableNow()`, capped by the balance).
- Action buttons (icon and label): "Send" (opens Send), "Receive" (Receive
  sheet), "Freeze" ("Freeze the wallet?": "One card tap stops every transfer.
  Unfreezing needs your phone and card, then waits <h>." with "Freeze" /
  "Cancel"; calls `freezeWithCard`). Send and Freeze are disabled while frozen
  or busy, and until the wallet state has loaded; while frozen, Freeze reads
  "Frozen".
- When frozen: an ice-blue notice "Wallet frozen" / "No transfers can go out.
  Unfreezing needs your phone and your card, then waits <h>." with "Request
  unfreeze".
- "Pending" with "See all", only while proposals are open: up to two
  ("Send · #<id>" or the settings change, the status, the amount); tap one for
  Activity.
- "Spent today": "<used> / <daily> NEAR" with a meter (amber above 85 %) and
  "Up to <x> NEAR per payment goes out instantly".

Receive sheet (`ReceiveSheet`): "Receive NEAR", a QR code of the account id,
the account id, "Copy" (turns into "Copied") and "Share", and the notice
"Testnet only".

### Send

- "Send NEAR" with a back button.
- "To" field (placeholder "name.testnet"; trimmed and lowercased as you type)
  with "Paste". Shows "That isn't a NEAR account name" or "That's this
  wallet's own account" under it.
- The amount, large and centred (placeholder "0"; the text shrinks as it gets
  longer), typed on the app's own keypad (`Keypad`: digits, ".", delete; hold
  delete to clear), so the system keyboard never covers it. "Available: <x>
  NEAR" (or "More than the wallet holds"), chips "Half" (half the spendable
  balance) and "Max instant" (the smaller of the instant allowance left and
  the spendable balance minus 0.01 NEAR).
- One line on speed: "You'll approve with your fingerprint and a card tap"
  before an amount, then "Goes out instantly" (green) or "Waits <h> · you can
  cancel it" (amber). "Instant" is the app's estimate with the phone's clock
  (`LimitInfo.isInstant`); the contract decides.
- "Review" (pinned under the keypad; enabled when idle with a valid receiver
  and an amount above 0 that the wallet holds) opens the review sheet "Review
  payment": the amount, "To", "From", "Speed", "Instant allowance left",
  "Network fee" / "Paid by your fee account", "Network", the two next steps
  ("Confirm with your fingerprint", "Tap your bank card on the phone"), a
  "Slide to approve" track (`SlideToConfirm`: drag the knob to the end; it
  springs back if let go early; accessibility services can activate it
  directly) that calls `send`, and "Edit". The sheet shows exactly the
  receiver and amount that `send` is called with.

### Activity (`ActivityScreen`)

"Activity" / "Approvals, delays and history", then "Pending · <n>" and one
glass card per open proposal (`ProposalCard`; `list_proposals`, up to 50):
title ("Send" or the settings change), "To <receiver, shortened> · #<id>", the
amount, and an approval tracker of three steps joined by lines that fill in
green: "Phone" and "Card" (ticked once that signer approved), then the delay:
"Delay" until fully approved, a countdown with a ring that fills as the delay
runs (ticks every second), "Ready", or "Void" / "Expired" in red. Under it a
status pill (`proposalStatus`): "Void: signers changed", "Waiting for
approvals", "Expired: it can no longer execute; cancel it", "Approved · can
execute in <time>", "Approved · wallet is frozen", "Approved · ready: tap
Execute". Empty: a tick that draws itself, "You're all caught up" / "Payments
waiting for an approval or a delay show up here."

| Button | Shown when | Calls |
|---|---|---|
| "Approve · card" | the card hasn't approved, its 10-minute window (`card_deadline_ts`) hasn't passed, not void | `approveWithCard` |
| "Approve · phone" | this phone hasn't approved, not void | `approveWithPhone` |
| "Execute" | `secondsToExecute` is 0, not expired, and not (frozen and a transfer) | `execute` |
| "Cancel" | not void | `cancel` |
| "Clean up" | `close_stale` would accept it by the phone's clock: void, never fully approved and older than 24 h, or fully approved more than the longest delay plus 7 days ago (8 days in app wallets) | `closeStale` |

`secondsToExecute` needs `ready_ts`; the delay is 0 for a NEAR transfer within
the limits right now, else `delays.transfer`; for a settings change it is
`delays.config` if this phone approved, else `delays.recovery`; counted from
`ready_ts` with the phone's clock. A proposal is expired once the phone's
clock is more than 7 days past the end of that delay (the contract's
`EXECUTION_WINDOW`).

Below: "History" → "All transactions" (the wallet on testnet.nearblocks.io).
The technical log is in Settings › Advanced.

### Card check (`CardCheck`)

- Before setup: "Card check" / "See whether a card can sign for Tapvault",
  with a back button to Welcome. After setup the same screen is "Your card",
  opened from Home (tap the card) or Settings, with a back button to where it
  was opened.
- The card picture (it flies in from Home): "•••• <last 4>" and the network
  from the last report, or from the enrolled card after setup; frosted while
  the wallet is frozen.
- After setup, before any check: the enrolled card ("<network> •••• <last 4>",
  "Contactless · <method label>", "RSA") and "Co-signs every payment" /
  "Together with this phone's key; neither alone moves funds", then "Check a
  card" / "See whether this or any other card can sign for Tapvault. Nothing is
  signed or stored."
- Before setup, before a check: what it does ("Reads the payment chip",
  "Checks it can sign", "Your card number stays here").
- `NfcNotice`, then "Check a card" ("Check another card" after one; disabled
  without NFC) calls `checkCard`; then the report card (`CardReportCard`: "This
  card can sign" with a tick that draws itself / "This card can't sign",
  "<network> •••• <last 4> · <label>", the notes, and "Technical details", open
  by default for a card that can't sign) and "Share report", which shares
  `CardReport.diagnostics()` as text ("Share card report"). Reading the report:
  [CARDS.md](CARDS.md#reading-a-card-check-report).
- The report and "Share report" show `AppViewModel.cardReport`, the last
  report in memory, so right after setup this screen already shows the
  enrolled card's report (until the next check, Forget or a restart).

### Settings (`SettingsScreen`)

- Profile card: avatar, wallet name, account (tap to copy), and a pencil to
  rename ("Wallet name" / "Shown on this phone only").
- "Wallets": "My wallets" ("<n> on this phone · switch or add one"; the
  switcher).
- "Your card": the enrolled card ("<network> •••• <last 4>", "Contactless ·
  <method label>") and "Check a card"; both open Your card.
- "Security": "Phone key" (`KeystoreSigner.hardwareLabel` · "this wallet only",
  or "none"; "P-256"), "Approvals" ("<threshold> of <n> signers for every
  payment; neither key alone can move funds"), "Delays" ("Transfers <h> ·
  changes <h> · recovery <h>"), "Limits" ("Instant up to <x> NEAR per payment ·
  <y> NEAR a day").
- "Emergency" while not frozen: "Freeze with phone" asks "Freeze with phone?"
  then calls `freezeWithPhone` (the fingerprint prompt follows).
- "Advanced": "Wallet account" and "Fee account" ("Pays network fees, can't
  move funds"), each opening testnet.nearblocks.io; "Contract storage"
  ("Reserved by the wallet contract, not spendable", "<x> NEAR"); "Technical
  log" (a sheet with the last 60 lines, see [Logs](#logs)).
- "About": "Network" "NEAR testnet" and the app version.
- "Forget this wallet on this phone": "Forget <name>?" / "This deletes this
  wallet's phone key. With a single card the wallet can't approve anything
  without it, so its NEAR stays locked in the wallet for good (on testnet that
  is only test NEAR). Your other wallets on this phone aren't affected." with
  "Forget" / "Keep". Calls `forgetWallet`.

Wallet switcher (`WalletPicker`, a bottom sheet): "My wallets", one row per
wallet (avatar, name, account or "Setup not finished" / "Not created yet",
balance, a tick on the active one), and "Add a wallet".

### Overlays

- **Result banner** (`OutcomeBanner`), pinned at the top, frosted glass: an
  icon (a tick that draws itself, with a haptic, or an alert), title, detail (scrolls past 180 dp), "View on explorer" when there
  is a link, and × to dismiss; a swipe up dismisses it too. Successes leave on
  their own after 5 s (7 s with a link); failures stay and buzz. Cleared when
  the next operation starts or on navigation.
- **Tap sheet** (`TapSheet`), a frosted sheet drawn in the app's window while
  a card tap is awaited: "Ready
  to scan", an animation of the card gliding onto the back of a phone, the
  request's title, then "Hold the card flat against the back of the phone,
  near the camera, for 2–3 seconds." or a retry hint (amber), and "Cancel".
  Once a card is in the field: "Reading card", "Keep holding the card…" /
  "Reading the chip and asking it to sign. Don't move the card until this
  closes.", with no Cancel.
- **Busy overlay** (`BusyOverlay`), a frosted card: spinner, the current step's label and "Keep
  the app open"; blocks touches; hidden while the tap sheet is up and on the
  Setup screen. It has no cancel.

## AppViewModel

### State

| Field | Holds |
|---|---|
| `screen` | the current `Screen` |
| `busy` | the running operation's label, or null (the busy guard) |
| `outcome` | the result card (`Outcome`: ok, title, detail, link) |
| `tap` | the tap sheet (`TapRequest`: title, reading, hint), or null |
| `wallet`, `balance`, `storageLocked`, `proposals` | the last `get_wallet`, spendable yoctoNEAR, yoctoNEAR held for storage, `list_proposals` |
| `cardReport` | the last `CardReport` (setup or Card check) |
| `offline` | the offline banner text, or null |
| `setupStep` | mirror of `Store.setupStep` |
| `log` | the in-app log, newest first, at most 200 lines |

Private: `pendingTag` (the tap being awaited), `earlyTag` (a card that arrived
while nothing waited), `refreshing`, `autoRefresh`, `activityRef` (a weak
reference to `MainActivity`, set in `onCreate` and `onResume`).

### `run`: one operation at a time

Every user operation goes through `run(label) { ... }`:

```
if (busy != null) return             // a second operation is ignored: no queue, no message
outcome = null; busy = label
viewModelScope.launch { withContext(Dispatchers.IO) { block() } }
  on any Throwable: note("error: <message>"); outcome = "That didn't work" / <message>
  finally: busy = null; tap = null
```

`progress(text)` replaces the busy label and logs the text. Most action
buttons are disabled while busy, and the overlays block touches.

### Setup: `beginSetup`, then `continueSetup`

`beginSetup(instant, daily)` parses both limits (a comma counts as a dot).
Problems give the result "Can't start yet" with one of: "Enter both limits as
amounts like 1 or 0.5.", "The per-day limit must be at least the at-once
limit.", "This phone has no NFC, so it can't read a bank card.", "Turn on NFC
in Android settings (Connected devices → NFC) first." Otherwise it saves them
exactly as typed (trimmed, a comma as a dot) to `Store.instantNear` and
`Store.dailyNear`, clears the last card report, opens Setup and calls
`continueSetup`.

`continueSetup` (label "Setting up") runs these steps in this order. Each
step is skipped if it already happened, so "Continue setup" resumes after a
crash, a lost connection or a failed tap.

| # | Progress label | Code | Skipped when | User sees | Saves |
|---|---|---|---|---|---|
| 0 | | `requireNfc()` | a read card is saved (`pending_card`), or step ≥ 3 | error if no NFC or NFC off | |
| 1 | "Creating your phone key in secure hardware" | `KeystoreSigner.create` | the key exists | nothing | Keystore alias `tapvault_phone_p256` |
| 2 | "Creating your wallet account on NEAR testnet" | `Onboarding.createAccounts(sponsor, plan)` | step ≥ 1 | nothing | `setup_plan` first; then `wallet`, `wallet_key`, `gas`, `gas_key`, step 1; `setup_plan` cleared |
| | | `TapvaultWallet.state()` (the chain check) | | | |
| 3 | "Tap your card" | `CardService.enrol` | a pending card is saved, step ≥ 3, or the chain already has a card | tap sheet "Hold your bank card to the back of the phone to add it" | `card_recipe`, `pending_card` |
| 4 | "Approve with your fingerprint to create the wallet" | `Onboarding.deployAndInit` (deploy + `new` in one transaction) | step ≥ 2, or the contract is already deployed | prompt "Create your Tapvault wallet" | step 2 |
| 5 | "Approve adding card ****<last4>" | `Onboarding.addCard` (`setup_add_signer`) | step ≥ 3, or the card is already on-chain | prompt "Add <Network> card ****<last4> as a signer" | step 3 |
| 6 | "Approve your limits to lock the wallet" | `Onboarding.finalize` (`setup_finalize`: threshold 2, `Delays.SHORTEST`, the NEAR limit) | step ≥ 4, or the chain says setup is over | prompt "Lock the wallet: up to <instant> NEAR at once, <daily> NEAR a day" | access-key check; step 4; `wallet_key` and `pending_card` cleared |

Then the screen switches to Home, a refresh starts, and the result is "Your
wallet is ready" / "Funds now move only with your phone and your card." with
a link to the wallet account.

Details:

- **Accounts** (`Onboarding.createAccounts`). Names `tv-<10 hex>` and
  `tv-<10 hex>-gas`, from an `Onboarding.Plan` (5 random bytes plus two fresh
  ed25519 keys) that is saved before anything is created, so a retry reuses
  accounts already made. Each account comes from the testnet faucet as
  `<name>.testnet` (10 NEAR) while the faucet cooperates; once it refuses, and
  if a sponsor is built in, accounts are created as `<name>.<sponsor>` funded
  with 5.5 NEAR (wallet) and 0.5 NEAR (fee account). An account that already
  exists with the plan's key is reused. Without a sponsor a faucet refusal
  stops setup: "The testnet faucet refused (<reason>). Try again later or from
  another network."
- **Chain check.** `get_wallet` on the wallet account. "No contract yet"
  (an error mentioning `CodeDoesNotExist`, `MethodNotFound`, `UNKNOWN_ACCOUNT`
  or "does not exist") means start from the deploy; any other error stops
  with "Can't reach NEAR testnet right now (<detail>). Try Continue setup
  again." so a step that may have landed is never sent twice.
- **Card first.** The card is read and signs its enrolment challenge
  (`Domain.cardEnrollChallenge(wallet, 1, keyHash)`) before anything is
  deployed. A card that can't sign, or whose key is outside the contract's
  rules (512 to 1984 bits, exponent 3 or 65537; checked before it is asked to
  sign), stops setup here: its report shows on the Setup screen and the error
  reads "This card can't sign for Tapvault: <reason>. Tap Continue setup to
  try another contactless card, or Start over." The read card is saved as a
  `PendingCard` until it is on-chain.
- **Who pays.** The deploy, add-card and finalize transactions are signed by
  the wallet account's own temporary key (`Store.walletKey`) and pay their own
  gas. `setup_finalize` deletes that
  key on-chain; the app then lists the account's keys and logs "wallet account
  locked: no keys left" or "WARNING: wallet still has keys: [...]". Every later
  call is signed by the fee account.
- **Errors** in the deploy, add-card and finalize steps: "deploy failed:
  <error>", "adding the card failed: <error>", "finalize failed: <error>", or
  "The card details from this setup are missing. Start over to set up again." Phone-key errors are under
  [The phone key](#the-phone-key-keystoresigner).
- **Log lines:** "phone key stored in <hardwareLabel>", "faucet unavailable
  (<reason>); funding <name> from <sponsor>", "wallet <account>, fee account
  <account>", "card <network> ****<last4> read (<bits>-bit key, <method
  label>)", "wallet contract already deployed" or "wallet contract deployed:
  <explorer link>", "card already added" or "card ****<last4> added as a
  signer", "wallet already locked", and with an unreadable sponsor key "sponsor
  key in this build is unreadable; using the faucet only".

### Start over: `startOver`

Label "Starting over". With a sponsor built in, it deletes the wallet account
if setup is at step 0 or 1 and its key is known, and deletes the fee account
at any step; the deleted accounts' NEAR goes to the sponsor (also for
accounts the faucet created). Without a sponsor (or with an unreadable
sponsor key) nothing is deleted on-chain. Then `resetCurrent` (back to Welcome)
and, if it tried to delete anything, the result "Started over" / "<items>.
Their NEAR went back to <sponsor>.", each item "deleted <account>" or
"couldn't delete <account> (<reason, cut to 80 characters>)", joined with
"; ". The closing sentence is there even when a deletion failed.

### Send: `send(receiver, amount)`

Label "Sending". In order:

1. Checks, before any prompt: the amount parses ("Enter an amount like 0.5")
   and is above 0 ("Amount must be more than zero"); the receiver is a NEAR
   account id, 2 to 64 characters ("That isn't a NEAR account id"); NFC is
   there and on; the wallet isn't frozen ("The wallet is frozen. Request an
   unfreeze first."); this phone is a signer ("This phone isn't a signer of the
   wallet"); a card is enrolled ("No card enrolled"); the receiver isn't the
   wallet ("That's this wallet's own account"); the spendable balance covers
   it ("The wallet only has <x> NEAR available to send"); the receiver exists
   ("<receiver> doesn't exist on NEAR testnet" when the RPC says so; a network
   error during this check stops the send with that error; 64-hex implicit
   accounts are allowed without existing).
2. **Reuse or propose.** If an open NEAR payment with the same amount and
   receiver exists that this phone approved and the card hasn't, isn't void,
   and whose card window ends more than 30 s from now, the app finishes that
   one (log "finishing unfinished payment #<id>"; no prompt). Otherwise
   "Approve on your phone", prompt "Send <x> NEAR to <receiver>", `propose`
   (log "proposal #<id> created").
3. "Waiting for the card challenge": polls `get_proposal` every 0.7 s for up to
   20 s until `card_challenge` is sealed (else "card challenge for proposal
   <id> was not sealed"; the proposal stays open, so sending again finishes it).
4. Tap sheet "Tap your card to approve sending <x> NEAR"; `CardService.sign`
   with the saved recipe. If this fails the error gets "Payment #<id> is saved
   and waits 10 minutes for your card: send the same amount again, or approve or
   cancel it in Activity." appended.
5. "Submitting". Within the limits (`LimitInfo.isInstant`, phone clock):
   `approve_card` and `execute` in one transaction, result "Sent <x> NEAR" /
   "to <receiver>" (log "sent: <link>"). If the contract refuses the `execute`
   part ("Delay has not elapsed", "Not enough approvals" or a frozen wallet),
   that transaction is undone and the same card signature is sent as
   `approve_card` alone: result "Approved, waiting" / "The wallet didn't treat
   this as within your limits (the phone's clock may be off). Execute it from
   Activity when it's ready." (log "approved #<id>; the contract didn't count
   it as instant, so it waits"). Over the limits: `approve_card` only,
   result "Approved, waiting <h> h" / "This is over your instant limit. In <h>
   h, open Activity and tap Execute. Until then you can cancel it or freeze
   the wallet." (log "approved #<id>; over the limit, executes after <h> h").

### Activity actions

| Function | Label | What it does | Result |
|---|---|---|---|
| `approveWithCard(p)` | "Approving" | Card challenge from the proposal (or waits for the seal); this phone's signer index; tap sheet "Tap your card to approve: <description>"; `approve_card` + `execute` in one transaction if it is a NEAR payment this phone approved, within the limits now and the wallet isn't frozen, else `approve_card` alone. If the contract refuses the `execute` part, `approve_card` is sent alone and then once more, and the second one fails with "Signer already approved" | "Approved and sent" or "Approved"; in the refused case "That didn't work" / "Signer already approved", although the card approval landed |
| `approveWithPhone(p)` | "Approving" | Prompt "Approve: <description>"; `approve_p256` over the intent hash read from `get_proposal` | "Approved" |
| `execute(p)` | "Executing" | `execute` (150 Tgas) | "Done" |
| `cancel(p)` | "Cancelling" | Prompt "Cancel: <description>"; `cancel` with a phone signature | "Cancelled" |
| `closeStale(p)` | "Cleaning up" | `close_stale` | none (the list refreshes) |

`approveWithCard` (before the tap), `approveWithPhone`, `cancel`,
`freezeWithPhone` and `requestUnfreeze` look up this phone's signer index
first (`phoneIndex`): a phone key that isn't a signer of the wallet gives
"This phone's key isn't a signer of this wallet. Forget the wallet in Settings
and set up again." (`send` has its own check, "This phone isn't a signer of
the wallet").

Descriptions come from `ProposalInfo.describe()`: "Send <x> NEAR to
<receiver>", or "Change settings: <ops>". The Home and Activity lists drop
the "Change settings: " prefix (e.g. just "Unfreeze"); prompts and tap
sheets keep it.

### Freeze and unfreeze

| Function | Label | What it does | Result |
|---|---|---|---|
| `freezeWithPhone()` | "Freezing" | Prompt "FREEZE the wallet (blocks all transfers)"; `freeze` with a phone signature over the current freeze nonce | "Wallet frozen" / "No transfers can go out until you unfreeze (a delayed change)." |
| `freezeWithCard()` | "Freezing" | Tap sheet "Tap your card to FREEZE the wallet"; `freeze` with the card's signature over `Domain.cardFreezeChallenge` | "Wallet frozen" / "Frozen with a card tap. Unfreezing needs your phone and card, plus a delay." |
| `requestUnfreeze()` | "Requesting unfreeze" | Checks NFC; reuses an open unfreeze request that this phone approved and the card hasn't, that isn't void and whose card window ends more than 30 s from now (no prompt); otherwise prompt "Unfreeze the wallet (takes effect after <h> h)" and proposes `ConfigOp.Unfreeze`; waits for the seal; tap sheet "Tap your card to approve unfreezing"; `approve_card` | "Unfreeze approved" / "It can execute in <h> h (Activity tab)." |

The unfreeze then needs "Execute" in Activity after the `config` delay (1 h in
app wallets). If the tap fails the proposal stays open (the error doesn't say
so): "Request unfreeze" again finishes it without a prompt while its card
window has more than 30 s left, or approve it with "Approve · card" in
Activity; after that, asking again creates a second one.

### Card check: `checkCard`

Label "Checking card". Clears the old report, shows the tap sheet "Hold the
card you want to check", runs `CardService.inspect` (the full enrolment probe
with a random challenge; nothing touches the chain or is saved), shows the
report, and logs "card check: <network> <method label or "can't sign">".

### Several wallets: `WalletIndex`

A phone holds any number of wallets, like accounts in a browser wallet, but
there is no seed phrase: each wallet's phone key is made inside the Keystore
and can't be exported or derived. Each wallet is fully separate: its own
wallet account and fee account (two new testnet accounts, so the faucet's
limit of about 10 accounts per IP per 12 h means about 5 wallets), its own
`Store` file, its own card enrolment (the same bank card may be enrolled in
several wallets) and its own Keystore key.

- `WalletIndex` (file `tapvault_wallets`) keeps the ids, the active id and a
  name per wallet ("Wallet 1", "Wallet 2", … renameable in Settings, stored
  on the phone only). Wallet 1 has id `0` and the original file `tapvault`
  and alias `tapvault_phone_p256`, so a wallet from an older build becomes
  wallet 1 unchanged.
- `AppViewModel.activeId` and `store` are the wallet on screen;
  `KeystoreSigner` reads its alias from `activeId` on every use. Every flow
  works on the active wallet only.
- `switchWallet(id)` is refused while anything runs (`busy`), so no flow
  continues on another wallet. A refresh that finishes after a switch is
  dropped (`refresh` checks `activeId`) and rerun for the new wallet.
- `addWallet()` registers a new id and opens Welcome for it (or just opens
  Welcome if the current wallet was never started). A new wallet that was
  never started (step 0, no setup plan, no card read) is removed when you
  switch away from it ("Back to my wallets" on Welcome).
- The switcher (`WalletPicker`) opens from the wallet name on Home, "My
  wallets" in Settings, or "Switch wallet" on a paused setup. It lists each
  wallet's name, account and spendable balance (`loadWalletBalances`), and
  "Add a wallet".

### Forget: `forgetWallet`, and Start over: `resetCurrent`

`forgetWallet` clears the active wallet's `Store`, deletes its Keystore key,
removes it from `WalletIndex`, clears the log and shows another wallet (a
finished one if there is one; with none left, an empty wallet 1 on Welcome).
Other wallets are untouched. Nothing happens on-chain. Used by "Forget" in
Settings.

`resetCurrent` (end of Start over) clears the active wallet's `Store` and
key but keeps it in the list under the same name, and opens Welcome.

### Refresh

- `refresh()` runs only after setup (step 4) and never twice at once
  (`refreshing`). On a background thread it reads `get_wallet`, the wallet
  account (`NearClient.spendable`: balance minus 10^19 yocto per byte of
  storage) and `list_proposals` (limit 50). A failure logs "refresh failed:
  <reason>" and shows the offline banner; success clears it.
- `startAutoRefresh()` starts one loop for the app's lifetime: every 8 s it
  refreshes if the activity is at least STARTED and the screen is Home or
  Activity. The UI also refreshes once at start, and most operations refresh
  when they finish.

### Prompt and tap texts

`KeystoreSigner.reason` is set before every phone signature and is the
prompt's subtitle:

| Operation | Prompt subtitle |
|---|---|
| Setup: deploy | "Create your Tapvault wallet" |
| Setup: add card | "Add <Network> card ****<last4> as a signer" ("Mastercard ****<last4>", since that name already ends in "card") |
| Setup: finalize | "Lock the wallet: up to <instant> NEAR at once, <daily> NEAR a day" |
| Send | "Send <x> NEAR to <receiver>" |
| Approve with phone | "Approve: <description>" |
| Cancel | "Cancel: <description>" |
| Freeze with phone | "FREEZE the wallet (blocks all transfers)" |
| Request unfreeze (new request only) | "Unfreeze the wallet (takes effect after <h> h)" |

| Operation | Tap sheet title |
|---|---|
| Setup | "Hold your bank card to the back of the phone to add it" |
| Send | "Tap your card to approve sending <x> NEAR" |
| Approve with card | "Tap your card to approve: <description>" |
| Freeze with card | "Tap your card to FREEZE the wallet" |
| Request unfreeze | "Tap your card to approve unfreezing" |
| Card check | "Hold the card you want to check" |

## Threads

- **Main thread:** Compose, the BiometricPrompt (posted with
  `runOnUiThread`), the auto-refresh loop's timer.
- **`Dispatchers.IO`:** every `run` block and every refresh. All of it is
  blocking code: `HttpURLConnection` RPC calls, `Thread.sleep` polling, card
  APDUs (`IsoDep.transceive` must not run on the main thread), and waits on
  `CompletableFuture`s for the card tap (up to 2 minutes) and the prompt (up to
  3 minutes).
- **NFC binder thread:** the reader-mode callback calls `vm.onTag(tag)`, which
  only completes the awaited future or stores the early tag.
- State written from the IO thread: `busy`, `outcome`, `tap`, `cardReport`,
  `setupStep`, `log`. The switch to Home after setup, `resetCurrent` and the
  "Started over" result after Start over, and the refresh results are posted
  with `withContext(Dispatchers.Main)`.
- **Busy guard:** one `run` at a time; a second call returns at once.
  `refresh` is separate and may run during an operation.
- **No cancellation** of network waits: the only cancels are the tap sheet's
  "Cancel" ("Card tap cancelled") and the prompt's own cancel ("Approval
  cancelled: <system message>"). With all three RPCs unreachable a send can
  stay busy for minutes before it reports.
- If the process dies mid-operation, the operation is lost. Setup resumes from
  saved state; an open payment can be finished by sending the same payment
  again, and an open unfreeze request by "Request unfreeze", while its
  10-minute card window has more than 30 s left (after that a new proposal is
  made).

## NFC and card taps

### Reader mode

`MainActivity.onResume` turns on NFC reader mode and `onPause` turns it off:

```kotlin
nfc?.enableReaderMode(
    this,
    { tag -> vm.onTag(tag) },
    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
)
```

While Tapvault is on screen every card goes to the app, so Android never pops
up "No supported app for this NFC tag" for a card placed early or left on the
phone. Platform sounds are off; the app vibrates for 90 ms when a card has
been read (`MainActivity.vibrate`), which some phones mute, so the tap sheet
says to hold the card "until this screen closes". `hasNfc` is "an NFC adapter
exists", `nfcEnabled` is "it is switched on".

### Waiting for a tap

- `onTag(tag)`: completes `pendingTag` if a tap is awaited, else stores the
  card as `earlyTag` with the time.
- `awaitTag()`: an early tag less than 30 s old is used at once (if the card
  has gone, connecting fails and that try is retried); otherwise it waits for
  the next tap.
- `withCard(title, block)` runs one card operation:

```
if Store.legacyDemoWallet: error "This wallet was set up with the old simulated demo card. ..."
up to 3 tries:
  activity in the foreground, NFC present, NFC on            (else an error, see below)
  tap = TapRequest(title, hint)                               (tap sheet)
  tag = awaitTag(), at most 2 minutes                         (else "No card was tapped. ...")
  tap = TapRequest(title, reading = true)                     ("Keep holding the card…")
  IsoDepTransceiver(tag): IsoDep.connect(), timeout 5,000 ms per command
  result = block(card); vibrate; return result
  TagLostException, IOException, SecurityException, WrongCardException:
      log the problem, set a hint, try again
after 3 failed tries: "That's not the card in this wallet. Use the card you added during setup." if any try was a different card,
  else "Couldn't read the card. Hold it flat against the back of the phone, near the camera, for 3 seconds."
```

Any other exception (a card that can't sign, "the card didn't sign (...)",
"the card signed, but not the way it did when it was added; tap it again", a
non-ISO-DEP tag) ends the operation at once.

| Problem | Logged | Hint on the next try |
|---|---|---|
| Card pulled away (`TagLostException`) | "card moved away (attempt <n>); hold it still" | "The card moved away too soon. Tap again and keep it still until this screen closes." |
| Link error (`IOException`) | "card link error: <reason>; tap again" | "The card connection dropped. Tap again and keep it still until this screen closes." |
| Stale tag after reader mode restarted (`SecurityException`) | "stale card connection: <reason>; tap again" | "Tap the card again and keep it still until this screen closes." |
| A different card signed: a signature in the enrolled layout that doesn't verify (`WrongCardException`) | "a different card was tapped" | "That's not the card in this wallet. Tap the card you added." |

| Error | When |
|---|---|
| "app is not in the foreground" | the activity is gone |
| "This phone has no NFC, so it can't read a bank card." | no NFC adapter |
| "Turn on NFC in Android settings (Connected devices → NFC), then try again." | NFC off |
| "No card was tapped. Try again and hold the card flat against the back of the phone." | 2 minutes without a card |
| "That's not the card in this wallet. Use the card you added during setup." | 3 failed tries, at least one of them a different card |
| "Card tap cancelled" | "Cancel" on the tap sheet |
| "This card doesn't speak ISO-DEP. Transit cards, stickers and key fobs can't sign; use a contactless bank card." | `IsoDep.get(tag)` is null |
| "This wallet was set up with the old simulated demo card. Forget it in Settings and set up again with your bank card." | the store has the old `demo_card` flag |

Practical: hold the card flat on the back of the phone over the NFC antenna
(often near the camera, on some phones in the middle), one card at a time, out
of metal or thick cases, and don't move it until the sheet closes. Enrolment
can run several card transactions in one tap ([CARDS.md](CARDS.md)), so it
takes longer than a payment tap.

### What runs during a tap

- Setup and Card check: `CardService.enrol` / `inspect` (certificate chain,
  the contract's rules for the card key, then signing plans).
- Payments, card approvals, card freeze, unfreeze: `CardService.sign` with the
  enrolled key from `get_wallet` and the saved `CardRecipe`. A signature in
  the enrolled terminal-data layout that doesn't verify means a different
  card (`WrongCardException`); signatures only in other layouts end with "the
  card signed, but not the way it did when it was added; tap it again".

## The phone key: `KeystoreSigner`

### The key

| | |
|---|---|
| Alias | One per wallet in `AndroidKeyStore`: `tapvault_phone_p256` (wallet 1), `tapvault_phone_p256_<id>` (others); `WalletIndex.keyAlias` |
| Algorithm | EC P-256 (`secp256r1`), `PURPOSE_SIGN`, `SHA256withECDSA` |
| Where | The TEE. StrongBox is not requested: on several phones a StrongBox key is created fine and then fails or stalls when it signs, which would strand the wallet |
| Auth | Required for every single use. Android 11+ (API 30+): `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`, so a strong biometric or the PIN / pattern / password. Android 9 and 10: `setUserAuthenticationValidityDurationSeconds(-1)`, strong biometric only |
| Biometric changes | `setInvalidatedByBiometricEnrollment(false)`: adding a fingerprint doesn't destroy the key |
| Public key | Read from the key's certificate, compressed to 33 bytes (`P256.compress`); it is on-chain as signer 0. With no key under the alias: "This phone's Tapvault key is missing. On testnet, forget the wallet in Settings and create a new one." |

`KeystoreSigner.create` (the first setup step): on Android 9 and 10 it first requires
an enrolled strong biometric ("On this Android version Tapvault needs a
fingerprint. Add one in Settings → Security, then try again."). Any other
exception except an `IllegalStateException` (shown as is), typically no screen
lock, becomes "Set up a screen lock (PIN, pattern or password) on this phone
first, then continue. (<detail>)" (the wrapping is in
`AppViewModel.continueSetup`).

`hardwareLabel` (Settings → "Phone key", and the log line "phone key stored in
..."): "Trusted Execution Environment" or "StrongBox secure chip" on Android 12+,
"secure hardware" on Android 9 to 11, "software (no secure hardware!)" if the
key isn't in secure hardware, "unknown" if it can't be read.

### Signing: `KeystoreSigner.sign(message)`

1. `Signature.initSign` with the key. A key invalidated by removing the screen
   lock gives "This phone's Tapvault key no longer works (the screen lock was
   removed). The wallet needs its recovery path; on testnet, forget it in
   Settings and create a new one."; a missing key gives "This phone's
   Tapvault key is missing. On testnet, forget the wallet in Settings and
   create a new one."
2. **Wait for the foreground** (`awaitForeground`): polls every 250 ms, up to
   3 minutes, until `MainActivity` is RESUMED, because a prompt requested
   while the app is in the background never appears. Then gives up with "Open
   Tapvault to approve with your fingerprint or screen lock."
3. **Prompt** on the UI thread: `BiometricPrompt` with a `CryptoObject` for the
   signature. Title "Tapvault", subtitle `reason`, description "Your phone key
   signs only what you approve here.", confirmation required (face unlock needs
   a tap on Confirm). Android 11+: strong biometric or device credential (the
   system offers the PIN). Android 9 and 10: strong biometric only, with a
   "Cancel" button.
4. Waits up to 3 minutes: "Approval timed out". Cancel or a lockout: "Approval
   cancelled: <system message>". Success without a signature object: "The
   phone didn't unlock its key. Try again."
5. Signs the 32-byte message and converts DER to raw `r || s` (64 bytes).

Only Class 3 ("strong") biometrics unlock the key. Most phones' face unlock is
weaker, so the prompt offers the fingerprint (or, on Android 11+, the PIN).

The AppCompat theme is there because on Android 9 and 10 the biometric library
can show its own fingerprint dialog, which needs it. Whenever `MainActivity`
pauses, reader mode turns off and comes back on at resume; a card tag from
before that is stale and is handled as a retry (`SecurityException`).

### What destroys or disables the key

- Uninstalling the app, or "Clear storage" for it in Android settings.
- "Forget this wallet on this phone" and "Start over" (`KeystoreSigner.delete`).
- A factory reset.
- Removing the screen lock (the key becomes permanently invalid).
- Android 9 and 10 only: removing every fingerprint. Android documents that
  `setInvalidatedByBiometricEnrollment(false)` keeps the key valid when
  biometrics are added or all removed, but the prompt on these versions
  accepts only a strong biometric, so nothing can unlock the key until a
  fingerprint is enrolled again. (Untested on a device.)

Keystore keys can't be exported, backed up or moved to another phone. With a
single card, a wallet whose phone key is gone can never approve anything
again. The app doesn't check Android key attestation, so the contract can't
tell that the key is hardware-backed ([ARCHITECTURE.md](ARCHITECTURE.md)).

## Local storage: `Store`

One SharedPreferences file per wallet (`tapvault` for wallet 1,
`tapvault_w_<id>` for the others; `WalletIndex.prefsFile`), `MODE_PRIVATE`. Every write uses
`commit()` (synchronous), so a crash right after a step can't lose it.
`android:allowBackup="false"` keeps it out of cloud and adb backups. Android's
documentation notes that for apps targeting API 31+ some manufacturers'
devices still copy app files in a device-to-device transfer, so the store
(including `gas_key`) could reach a new phone that way; the Keystore key
never does.

| Key | Property | Holds | Secret? | Written / cleared |
|---|---|---|---|---|
| `wallet` | `walletAccount` | wallet account id | no | when the accounts exist / Forget |
| `wallet_key` | `walletKey` | the wallet account's full-access ed25519 key (`ed25519:<base58>`) | **yes**, until setup ends | when the accounts exist / after finalize |
| `gas` | `gasAccount` | fee account id | no | when the accounts exist / Forget |
| `gas_key` | `gasKey` | the fee account's full-access key; pays all gas | **yes**; it can spend the fee account's NEAR, nothing else | when the accounts exist / Forget |
| `step` | `setupStep` | 0 nothing, 1 accounts, 2 deployed, 3 card added, 4 finalized | no | after each setup step / Forget |
| `setup_plan` | `setupPlan` | `Onboarding.Plan.encode()`: `tag\|walletSecret\|gasSecret` | **yes** | before any account is created / once the accounts exist |
| `pending_card` | `pendingCard` | `PendingCard.encode()`, below | no PAN; the proof only works for this wallet's setup | after the card step / after finalize |
| `card_recipe` | `cardRecipe` | `CardRecipe.encode()`, e.g. `A0000000041010:CDA:100:0:0` | no | after the card step / Forget |
| `card_network`, `card_last4` | `cardNetwork`, `cardLast4` | the enrolled card's network name and last four digits, for the card art and Security rows (never more of the PAN) | no | after the card is read at enrolment / Forget |
| `instant`, `daily` | `instantNear`, `dailyNear` | the limits from the Limits screen, as typed (a comma saved as a dot; defaults "1", "2") | no | "Continue" on Limits / Forget |
| `demo_card` | `legacyDemoWallet` | set only by old builds that had a simulated demo card; with step > 0 it blocks every card tap | no | never written now |

`PendingCard.encode()` is nine fields joined by `|`: modulus (hex), exponent,
`un_offset`, `un_len`, `terminal_data_len`, SDAD (hex), terminal data (hex),
network, last four digits. It lets "Continue setup" add the card without
another tap. `CardRecipe` is explained in [CARDS.md](CARDS.md#cardrecipe-how-this-card-signs).

Nothing in the store can move the wallet's funds once setup is finished: that
takes the phone key and the card. In memory only (lost when the process dies):
the screen, the wallet view, proposals, the card report and the log.

## Network

`AppViewModel.near` is one `NearClient()` with the default testnet RPCs, tried
in order and falling back on failure: `https://rpc.testnet.fastnear.com`,
`https://test.rpc.fastnear.com`, `https://rpc.testnet.near.org`. Faucet:
`https://helper.nearprotocol.com/account`. Explorer links:
`https://testnet.nearblocks.io/txns/<hash>` and `.../address/<account>`. A
signed transaction is only re-sent byte for byte; it is signed again with a
fresh nonce only when the chain refused it unrun (`InvalidNonce` and its hash
unknown), with at most three signing attempts in all. A timeout is followed
up by looking the transaction up by hash, and after about two minutes the
error is "The network didn't confirm transaction <hash> in time. It may still
go through: check Activity (or <explorer link>) before trying again." Full
detail (retries, timeouts, gas): [ARCHITECTURE.md](ARCHITECTURE.md#the-near-client).

## Build configuration

`android/app/build.gradle.kts`.

### Versions

| | |
|---|---|
| `applicationId` / `namespace` | `app.tapvault` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 35 / 35 |
| `versionCode` / `versionName` | 1 / `0.1.0` (the same for every build) |
| Java / Kotlin target | 17 |
| Build types | `debug`; `release` is also signed with the debug key, no minify |
| Gradle JVM heap | `-Xmx3g` (`android/gradle.properties`) |

### The sponsor (`BuildConfig`)

```kotlin
buildConfigField("String", "SPONSOR_ACCOUNT", "\"${local.getProperty("tapvault.sponsor.account", "")}\"")
buildConfigField("String", "SPONSOR_KEY", "\"${local.getProperty("tapvault.sponsor.key", "")}\"")
```

read from the untracked `android/local.properties`. `AppViewModel.sponsor()`
uses them only when both are non-blank and the key can be read. An unreadable
key is ignored (log "sponsor key in this build is unreadable; using the
faucet only"): setup uses the faucet only, Start over deletes nothing, and its
dialog shows the no-sponsor text (`hasSponsor`). The sponsor is a funded
testnet account whose sub-accounts become wallets when the faucet refuses
(owner's sponsor: `tapvault-skyyy.testnet`, about 51 NEAR on 12 Nov; each
sponsored wallet costs 6 NEAR).

- The key is compiled into the APK as plain text: anyone with the APK can
  extract it and spend the sponsor's testnet NEAR. Testnet only; never use a
  mainnet key.
- To build without it, leave both lines out of `local.properties` (or empty)
  and rebuild. Changing them always needs a rebuild.
- Never commit `local.properties` (it is gitignored).

```properties
# android/local.properties
sdk.dir=/path/to/Android/sdk
# optional, testnet only, compiled into the APK:
tapvault.sponsor.account=<funded-account>.testnet
tapvault.sponsor.key=ed25519:<that account's private key>
```

### The contract wasm

The app deploys the wallet contract itself, so the APK carries it:

- `verifyWasm` checks that `contract/res/tapvault_contract.wasm` has SHA-256
  `3ea83cc88fecda3c727d624e5a935ed1fe20cc5623cafafa4e86969bf7cdc2ec`
  (`contractWasmSha256`; NEAR `code_hash`
  `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`). Failures: "Missing
  <path>: it is committed to the repo; restore it with git." or
  "contract/res/tapvault_contract.wasm has SHA-256 <hash>, expected <hash>. If
  you changed the contract on purpose, update contractWasmSha256 here and the
  hash in README.md."
- `copyWasm` (after `verifyWasm`) copies it into `app/src/main/assets/`;
  `preBuild` depends on it.
- At the deploy step of setup the app reads the asset and sends
  `DeployContract` + `new` (100 Tgas) in one transaction from the wallet
  account. The app doesn't re-check the hash at runtime; the build did.

Wallets keep the code they were deployed with: a new APK with a new contract
affects only new wallets. Changing the contract: see
[`contract/res/README.md`](../contract/res/README.md).

### Manifest

`android/app/src/main/AndroidManifest.xml`:

| Setting | Value | Why |
|---|---|---|
| Permissions | `INTERNET`, `NFC`, `USE_BIOMETRIC`, `VIBRATE` | RPC, card reads, the prompt, tap feedback |
| `uses-feature android.hardware.nfc` | `required="true"` | Play-store filtering; a sideloaded APK still installs on a phone without NFC, which then shows the no-NFC messages |
| `android:allowBackup` | `false` | keeps the store out of backups |
| Theme | `@style/Theme.Tapvault` (AppCompat, no action bar) | needed for the fingerprint dialog on Android 9/10 |
| `MainActivity` | `exported="true"`, `launchMode="singleTop"`, `screenOrientation="portrait"`, `windowSoftInputMode="adjustResize"` | single launcher activity |
| `configChanges` | `uiMode\|fontScale\|density\|screenSize\|smallestScreenSize\|screenLayout\|orientation\|keyboard\|keyboardHidden\|navigation\|locale\|layoutDirection` | the activity isn't recreated on these changes, so a tap, a prompt or an `IsoDep` link in progress survives them; the view model survives recreation anyway |

## Building the APK

Needs:

- **JDK 17.** `android/build-apk.sh` finds one. Gradle 8.11.1 can't run on the
  newest JDKs, and `:core` asks for a JDK 17 toolchain (`jvmToolchain(17)`)
  anyway.
- **Android SDK** with `platforms;android-35` and `build-tools;34.0.0` (the
  default build tools of Android Gradle Plugin 8.7.3; `build.gradle.kts`
  doesn't pick a version, and the first build downloads them if they are
  missing and the SDK licences are accepted):
  `sdkmanager "platforms;android-35" "build-tools;34.0.0"`. Point `sdk.dir`
  in `android/local.properties` (or `ANDROID_HOME`) at the SDK.
- Network access on the first build (Gradle and dependencies download).

```bash
cd android
./build-apk.sh
# Using JAVA_HOME=...
# APK: <repo>/android/app/build/outputs/apk/debug/app-debug.apk
```

`build-apk.sh` uses `$JAVA_HOME` if it is a JDK 17; otherwise it tries, in
order, `/usr/libexec/java_home -v 17` (macOS), Homebrew's `openjdk@17` under
`/opt/homebrew` and `/usr/local`, `/usr/lib/jvm/java-17-openjdk-amd64`,
`/usr/lib/jvm/java-17-openjdk`, `/usr/lib/jvm/temurin-17-jdk-amd64`. None:
"JDK 17 not found. Install it (macOS: brew install openjdk@17) or point
JAVA_HOME at one." Then it runs `./gradlew :app:assembleDebug`.

By hand: `JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleDebug` (Windows:
`gradlew.bat` with `JAVA_HOME` set). Never run two Gradle builds of this
project at the same time (a concurrent run once made a test task report a
false failure). Tests: `./gradlew :core:test`, see [TESTING.md](TESTING.md).

## Installing and updating

### With adb

On the phone: Developer options → USB debugging on; connect; accept the
computer's key.

```bash
# from the repository root
adb devices                       # the phone must show as "device"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` replaces an installed app **and keeps its data and phone key**. Use it
for every update.

### Without adb (sideloading)

Copy the APK to the phone and open it from the Files app. Allow "Install
unknown apps" for that app when Android asks.

### Phone-specific warnings

- **Google Play Protect** may call the app unrecognised or offer to scan it:
  "Install anyway" (or "Install without scanning"). A scan may send the APK to
  Google, including the sponsor key if one is built in (testnet only).
- **Samsung (One UI 6 and later):** Auto Blocker blocks sideloading and USB
  installs; turn it off in Settings → Security and privacy while installing.
- **Xiaomi, Redmi, POCO (MIUI / HyperOS):** `adb install` needs Developer
  options → "Install via USB" on (it may ask you to sign in to a Mi account),
  and the phone asks to confirm. Its security scan may warn about the app.
- **OPPO, realme, OnePlus (ColorOS / realme UI / OxygenOS):** confirm the
  install prompt that appears on the phone during `adb install`; the app
  scanner may warn.

### Updating: same signing key

Android only updates an app signed with the same key. Debug APKs are signed
with the build machine's own `~/.android/debug.keystore`, so an APK built on
another computer fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Keep
installing builds from the same computer (or copy that computer's
`debug.keystore` to the other one). **Never uninstall to get around it on a
phone that holds a wallet you still need**: uninstalling deletes the phone
key, and with one card the wallet can never approve anything again. The same
goes for "Clear storage". Only if that wallet doesn't matter (test NEAR only):
`adb uninstall app.tapvault`, then install, and set up a new wallet.

A new APK changes the app, not existing wallets' contract code.

## Logs

### The in-app log

Settings → Advanced → "Technical log": newest first, `HH:mm:ss  message`, the last 60 lines of
up to 200. Held in memory only: it is lost when the process dies and cleared
by Forget and Start over, and it can't be shared (take screenshots:
`adb exec-out screencap -p > screen.png`). It records every progress step,
every error ("error: <message>"), tap problems, setup results, refresh
failures and Card check results. It never holds the card number (only the
last four digits) or keys.

The Card check report can be shared ("Share report"; on the Setup screen
"Share card report"); that is the main artefact for card problems
([CARDS.md](CARDS.md#when-a-card-fails-what-to-collect)).

### logcat

The app writes nothing to logcat itself (no `android.util.Log` calls), and
errors inside operations are caught and shown in the app. logcat is for
crashes and for the platform's NFC, Keystore and biometric messages:

```bash
adb logcat -c                                          # clear old lines first
adb logcat --pid=$(adb shell pidof -s app.tapvault)    # this app's process (start the app first)
adb logcat -b crash                                    # crash stack traces
adb logcat | grep -iE "nfc|keystore|biometric|AndroidRuntime"
```

## Limitations and known issues

Product limits (see also [STATUS.md](STATUS.md)):

- Untested on a real phone; the Android 9 / 10 path is untested.
- No simulated or demo card in the app, and the emulator has no NFC, so the
  whole flow can't run on an emulator.
- One wallet per phone, one card per wallet, NEAR only, testnet only. The only
  settings change the app proposes is Unfreeze; there is no recovery with one
  card.
- Nothing executes by itself: over-limit payments and unfreezes need "Execute"
  in Activity after the delay.
- `approve_card` and `execute` share one transaction. If the contract refuses
  the `execute` part as too early, short of approvals or frozen, the app sends
  the card approval again on its own; after any other `execute` failure the
  card approval is discarded too.
- Long network waits have no cancel button.
- The sponsor key ships inside APKs built with it.
- "Approve · phone" signs the intent hash read from the RPC rather than
  recomputing it ([ARCHITECTURE.md](ARCHITECTURE.md)); the app's own flows don't
  need that button.

Code-level issues found while writing this page (not fixed):

- "Approve · card" in Activity, when the contract refuses the `execute` part:
  `approveWithCard` sends `approve_card` alone (it lands) and then sends it
  again; the second call fails with "Signer already approved", so the result
  is "That didn't work" although the card approval is on-chain.
- The "Started over" result ends with "Their NEAR went back to <sponsor>."
  even when it lists a failed deletion.
- The busy guard drops a second operation silently.

## Related docs

- [OVERVIEW.md](OVERVIEW.md): the user journey and glossary.
- [ARCHITECTURE.md](ARCHITECTURE.md): components, flows, where state lives, the NEAR client.
- [CARDS.md](CARDS.md): the card protocol, CA keys, Card check reports.
- [CONTRACT.md](CONTRACT.md): the contract methods the app calls.
- [TESTING.md](TESTING.md): automated tests and the real-phone test plan.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): every user-visible error.
- [STATUS.md](STATUS.md): what is verified, known issues, next steps.
- [`contract/res/README.md`](../contract/res/README.md): the shipped contract build.
