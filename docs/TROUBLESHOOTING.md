# Troubleshooting

Every message the app can show, where it comes from in the code, what it
means and what to do. Last updated 12 Nov 2026.

Messages are quoted exactly as the code builds them. `<…>` marks a part that
changes (an amount, an account, a card's AID). Code locations are
`Class.function` in `android/app/src/main/kotlin/app/tapvault/` (app) or
`android/core/src/main/kotlin/app/tapvault/core/` (client library), and
constant names in `contract/src/error.rs`.

Nothing has been tested with a real card or a real phone's NFC yet, so some
of these paths have only run in tests or never run at all
([STATUS.md](STATUS.md)). When you hit one, collect the evidence listed in
[TESTING.md](TESTING.md#what-to-send-back).

Contents:

- [How problems show up](#how-problems-show-up)
- [Install and build](#install-and-build)
- [Setup](#setup)
- [NFC and card](#nfc-and-card)
- [Phone key (fingerprint and screen lock)](#phone-key-fingerprint-and-screen-lock)
- [Sending, Activity and freeze](#sending-activity-and-freeze)
- [Network and RPC](#network-and-rpc)
- [Contract errors](#contract-errors)
- [Reading the Card check report](#reading-the-card-check-report)
- [Faucet and sponsor funding](#faucet-and-sponsor-funding)

## How problems show up

| Where | What you see | Notes |
|---|---|---|
| Result banner | A banner pinned to the top of the screen with a red icon: title `That didn't work` (or `Can't start yet` on the limits screen), the message under it; tap `×` or swipe it up to close (moving to another screen or starting another action also closes it). Successes use the same banner with a green icon and close by themselves after a few seconds | Every failed action ends here (`AppViewModel.run`). The message is also added to the Log as `error: <message>`. `Can't start yet` is not logged |
| Busy overlay | Spinner and a label such as `Waiting for the card challenge` | No cancel button. Each network call retries across three RPCs, so with no connection a step can take minutes before it fails |
| Tap sheet | Card picture, a title saying what the tap is for, a hint line, `Cancel` | A hint after a failed read is not an error: the sheet waits for another tap, up to three attempts |
| Fingerprint prompt | System dialog titled `Tapvault`; the subtitle is what you approve; `Your phone key signs only what you approve here.` | Dismissing it gives `Approval cancelled: …` |
| Home banner | `Can't reach NEAR testnet right now. Showing the last known state; retrying.` | The background refresh (every 8 s on Home and Activity) failed |
| Card check report | Badge `Can't sign` and notes | Not an error box; see [Reading the Card check report](#reading-the-card-check-report) |
| Activity status | `Waiting for approvals`, `Void: signers changed`, `Approved · wallet is frozen`, … | See [Sending, Activity and freeze](#sending-activity-and-freeze) |
| Log | Settings → Advanced → Technical log: newest first, each line starts with the time | Kept in memory only (200 lines kept, 60 shown); gone when the app process ends, and cleared by Start over and by Forget; can only be opened once a wallet exists (not during setup). Nothing is written to the Android system log |

Composite messages:

- Setup steps prefix the chain's reason when their transaction ran and
  failed: `deploy failed: <reason>`, `adding the card failed: <reason>`,
  `finalize failed: <reason>`. Network and RPC errors on the same steps arrive
  without a prefix ([Network and RPC](#network-and-rpc)).
- On Send, any failure at the card step gets this added:
  `<message>` + blank line + `Payment #<n> is saved and waits 10 minutes for your card: send the same amount again, or approve or cancel it in Activity.`
- Contract errors arrive without NEAR's `Smart contract panicked: ` prefix
  (`NearClient.readableError` removes it). The explorer shows the prefix.
- A technical text such as `java.lang.NullPointerException` or `Check failed.`
  means an unexpected state: report it with the Log.

## Install and build

| Message | From | Meaning | What to do |
|---|---|---|---|
| Installer says the app isn't installed, or the package conflicts with an existing one (wording varies) | Android package installer | A Tapvault build signed with another key is installed. Debug builds made on different computers have different keys | Uninstall the old app first. That deletes its phone key, and its wallet can't be used again (test NEAR only) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb install` | Same as above | `adb uninstall app.tapvault`, then install |
| `INSTALL_FAILED_OLDER_SDK` | `adb install` | Android older than 9 (`minSdk` 28) | Use a phone with Android 9 or newer |
| `INSTALL_FAILED_USER_RESTRICTED` | `adb install` | The phone refused a USB install (Xiaomi: "Install via USB" is off) | Enable it in Developer options; accept the prompt on the phone |
| Play Protect, MIUI or ColorOS warning or scan | The phone | Unknown, debug-signed app | Let it scan; install anyway |
| `JDK 17 not found. Install it (macOS: brew install openjdk@17) or point JAVA_HOME at one.` | `android/build-apk.sh` | No JDK 17 found (Gradle 8.11.1 here needs 17) | Install JDK 17 or set `JAVA_HOME` |
| `contract/res/tapvault_contract.wasm has SHA-256 <hash>, expected bbb34d0898d18ab7bb8687c61ba8605def6e7c9a33be91659b199d0834ca2f1c. If you changed the contract on purpose, update contractWasmSha256 here and the hash in README.md.` | `android/app/build.gradle.kts`, task `verifyWasm` | The shipped contract file changed | `git checkout contract/res/tapvault_contract.wasm`; for a deliberate change follow [`contract/res/README.md`](../contract/res/README.md) |
| `Missing <path>: it is committed to the repo; restore it with git.` | `verifyWasm` | The shipped contract file is missing | Restore it with git |
| Gradle can't find the Android SDK | Android Gradle plugin | `sdk.dir` not set | Set `sdk.dir` in `android/local.properties` ([ANDROID.md](ANDROID.md)) |
| `FileNotFoundException` for a `tapvault_contract.wasm` path | `TestnetE2ETest` | The live test deploys the file named by `TAPVAULT_WASM` from your environment, or `contract/res/tapvault_contract.wasm` when it isn't set (`android/core/build.gradle.kts`), and that file is missing | Unset `TAPVAULT_WASM` or give it the absolute path of an existing build; restore `contract/res/tapvault_contract.wasm` with git ([TESTING.md](TESTING.md#live-testnet-test)) |
| `No enum constant app.tapvault.core.SimCard.Profile.<X>` | `TestnetE2ETest` | Bad `TAPVAULT_CARD_PROFILE` | Use `DDA`, `VISA_FDDA` or `MASTERCARD_CDA` |
| A test task fails for no clear reason while another Gradle build runs | Gradle | Two builds of the same project at once | Run one Gradle build at a time; check with `./gradlew --status` |

## Setup

| Message | From | Meaning | What to do |
|---|---|---|---|
| `Can't start yet` + `Enter both limits as amounts like 1 or 0.5.` | `AppViewModel.beginSetup` | A limit is empty, not a number, or not above 0. Rare: the limits screen keeps Continue disabled until both are valid | Fix the limits on "Spending limits", or switch limits Off |
| `Can't start yet` + `The per-day limit must be at least the at-once limit.` | `AppViewModel.beginSetup` | "Instant payment limit" is larger than "Daily limit". Rare: the limits screen shows `The daily limit must be at least the instant limit.` under the field and keeps Continue disabled | Fix the limits |
| `Can't start yet` + `This phone has no NFC, so it can't read a bank card.` | `AppViewModel.beginSetup` | No NFC hardware, and the setup is *With my card* | Use a phone with NFC, or set **Bank card** to **Later** on the limits screen (a wallet without a card; add the card later on a phone with NFC) |
| Continue is greyed out on the limits screen | `Onboarding.kt` `LimitsScreen` | With a card, the phone has no NFC or NFC is off; or a limit isn't valid | Turn NFC on, fix the limits, or set **Bank card** to **Later** |
| **Continue without a card** on the setup screen | `SetupProgress` → `AppViewModel.continueWithoutCard` | Shown during a *With my card* setup until the card is added. It drops the card read so far and locks the wallet with threshold 1 (phone only) | Use it when your card won't sign or isn't at hand; add a card later in Security |
| `Can't start yet` + `Turn on NFC in Android settings (Connected devices → NFC) first.` | `AppViewModel.beginSetup` | NFC is off | Turn NFC on |
| `The testnet faucet refused (<reason>). Try again later or from another network.` | `Onboarding.createAccounts` | The faucet failed and this APK has no sponsor | See [Faucet and sponsor funding](#faucet-and-sponsor-funding) |
| `faucet did not create <account>` (never on its own: it is the reason inside `The testnet faucet refused (…)`, or in the Log line `faucet unavailable (…)` when a sponsor takes over) | `NearClient.createTestnetAccount`, caught by `Onboarding.createAccounts` | The faucet answered but the account didn't appear within about 30 s (20 checks, 1.5 s apart) | **Continue setup**. Names and keys are saved before anything is created, so an account that appears later is reused |
| `creating <account> failed: <reason>` | `NearClient.createSubAccount` | The sponsor couldn't create the account | See [Faucet and sponsor funding](#faucet-and-sponsor-funding) |
| `Can't reach NEAR testnet right now (<detail>). Try Continue setup again.` | `AppViewModel.continueSetup` | The wallet's on-chain state couldn't be read; the app won't risk repeating a step that may have landed | Restore internet; **Continue setup** |
| `This card can't sign for Tapvault: <reason>. Tap Continue setup to try another contactless card, or Start over.` | `AppViewModel.continueSetup` (a `CardCheckFailed`) | Reading the card or making it sign failed, or the card's key is outside the contract's rules (checked before any signing attempt). The report is shown on the Setup screen. Normally nothing has been deployed yet (the card is read before the deploy) | Tap **Share card report** under the report and send it. **Continue setup** and tap another card. Reasons: [Card check reasons](#reasons-in-the-report-and-in-card-errors) |
| `deploy failed: <reason>` | `Onboarding.deployAndInit` | Deploy + `new` failed. With `Not enough NEAR: the account must keep enough to pay for its storage` the wallet account has less than about 3.35 NEAR (it should have 10 from the faucet or 5.5 from the sponsor) | **Continue setup**. If NEAR is missing, report it, and send testnet NEAR to the account shown on the Setup screen (`Wallet account: <account>`) from any testnet account, then **Continue setup** |
| `adding the card failed: <reason>` | `Onboarding.addCard` | `setup_add_signer` refused, for example `Invalid signer key` or `Card signature verification failed` (see [Contract errors](#contract-errors)) | Report it with the card report. **Continue setup** retries the same saved card read, so to use another card you must **Start over** |
| `finalize failed: <reason>` | `Onboarding.finalize` | `setup_finalize` refused | Report it with the Log |
| `The card details from this setup are missing. Start over to set up again.` | `AppViewModel.continueSetup` | A safety check before `setup_add_signer`. Not expected: when the saved card read is gone and the card isn't on-chain, **Continue setup** asks for a new tap instead | **Start over**; report it |
| `Set up a screen lock …`, `On this Android version Tapvault needs a fingerprint. …` | Phone key | See [Phone key](#phone-key-fingerprint-and-screen-lock) | |
| Log: `WARNING: wallet still has keys: [<keys>]` | `AppViewModel.continueSetup` | After locking, the wallet account still has access keys | Serious. Report with the wallet account name |
| Log: `wallet contract already deployed`, `card already added`, `wallet already locked` | `AppViewModel.continueSetup` | Continue setup found that step already on-chain and skipped it | Nothing; this is the resume working |
| Log: `faucet unavailable (<reason>); funding <name> from <sponsor>` | `Onboarding.createAccounts` | The faucet refused; the built-in sponsor paid | Nothing |
| Log: `sponsor key in this build is unreadable; using the faucet only` | `AppViewModel.sponsor` | The APK has a sponsor account and key built in, but the key can't be read. Setup uses only the faucet, and Start over deletes nothing | If the sponsor is needed, fix `tapvault.sponsor.key` and rebuild ([Faucet and sponsor funding](#faucet-and-sponsor-funding)) |
| Dialog `Start over?` (buttons `Start over` / `Keep going`) | `Screens.kt` `SetupProgress`, `AppViewModel.startOver` | Without a usable sponsor in the build: `The phone key and this setup's saved state are removed. The accounts made so far stay on testnet with their test NEAR.` With one, before the contract is deployed: `The accounts made for this setup are deleted (their NEAR goes back to the sponsor) and the phone key is removed.`; after that: `The half-built wallet is left on testnet with its test NEAR. The fee account is deleted and the phone key removed.` | Confirm only if you want to abandon this setup. The phone key and all saved setup state are always deleted |
| `Started over` + `deleted <account>; deleted <account>. Their NEAR went back to <sponsor>.` | `AppViewModel.startOver` | Shown on Welcome after Start over in a build with a sponsor: the accounts it deleted (the wallet account only before the deploy, the fee account always). A failure is listed as `couldn't delete <account> (<reason>)`; the closing sentence is there anyway, but that account keeps its NEAR. Without a sponsor nothing is deleted and no result is shown | Nothing. An account it couldn't delete stays on testnet with its test NEAR; check the sponsor account's recent transactions on the explorer if in doubt |
| `no wallet yet` | `AppViewModel.walletClient` | No wallet account is stored | Shouldn't appear; report it |

## NFC and card

| Message | From | Meaning | What to do |
|---|---|---|---|
| `This phone has no NFC, so it can't read a bank card.` (Welcome shows the chip `No NFC on this phone`; the limits screen shows `This phone has no NFC` / `It can't read a bank card. Use an Android phone with NFC.`) | `AppViewModel.withCard`, `requireNfc`; `Onboarding.kt` `NfcNotice`, `NfcChip` | No NFC adapter | Use a phone with NFC |
| `Turn on NFC in Android settings (Connected devices → NFC), then try again.` (Welcome: the chip `NFC is off · Turn on`, which opens the NFC settings; the limits screen: `NFC is off` / `Turn it on to tap your card (Connected devices → NFC).` with an `Open NFC settings` button) | `AppViewModel.withCard`, `requireNfc` | NFC is off. Send and Request unfreeze check this before the fingerprint prompt; Continue setup checks it only while the card still has to be read | Turn NFC on |
| `app is not in the foreground` | `AppViewModel.withCard`, `requireNfc` | The screen was gone when the tap was needed | Open the app; try again |
| Hint `The card moved away too soon. Tap again and keep it still until this screen closes.` | `AppViewModel.withCard` (`TagLostException`) | The card left the field mid-read | Hold it flat and still until the sheet closes (2–3 s) |
| Hint `The card connection dropped. Tap again and keep it still until this screen closes.` | `AppViewModel.withCard` (`IOException`) | Link error or a command over the 5 s limit (`IsoDepTransceiver`) | Hold still; try another spot (the antenna is often near the camera); remove thick or metal cases |
| Hint `Tap the card again and keep it still until this screen closes.` | `AppViewModel.withCard` (`SecurityException`) | Android invalidated the card connection | Tap again |
| Hint `That card isn't one of this wallet's cards. Tap a card you added.` | `AppViewModel.withCard` (`WrongCardException` from `CardService.identify`) | The card was read and its certified key matches none of the wallet's card signers, so it was refused before signing anything (Log: `a card that isn't one of this wallet's was tapped; it signed nothing`). Also shown for a new backup card tapped to approve its own addition: it isn't a signer until the change executes | Tap a card of this wallet (main or backup). If you did, report it with the Log and that card's report |
| `That card isn't one of this wallet's cards. Use a card you added to this wallet.` | `AppViewModel.withCard` | Three attempts failed (six with the hold timer on) and at least one of them was a different card (the hint above) | Try again with a card of this wallet (on Send: the same amount to the same account within 10 minutes finishes the saved payment). If it was one of them, report it with the Log and that card's report |
| `couldn't read the card's key (<reason>)` | `CardService.identify` | Every tap first reads the card's certificate chain to tell which of the wallet's cards it is; that failed. With a card of another network it is usually `no CA key for … index …` or a SELECT `6A82` | Tap a card of this wallet. If it is one, run Card check on it and report both |
| Hint `<card> already approved this. Tap another card of this wallet.` | `AppViewModel.approveWithCard` | Activity → `Approve · card` with a card whose approval is already counted | Tap a different card of the wallet, or nothing: one card approval is all a payment needs |
| Hint `Card moved, hold it again. The timer starts over.` | `AppViewModel.withCard` (`TagLostException` during the hold timer) | The card was lifted during the hold countdown, so nothing was signed | Hold it on the phone until the ring is full. Settings → Security Center → `Hold card to sign` sets 3, 5 or 10 s, or Off |
| `Couldn't read the card. Hold it flat against the back of the phone, near the camera, for 3 seconds.` | `AppViewModel.withCard` | Three attempts in a row failed (moved away, link errors or stale connections), none of them with a different card | Find the antenna spot with Card check; hold for 3 seconds |
| `the card signed, but not the way it did when it was added; tap it again` | `CardService.sign` | The card's signatures came back, but none in the terminal-data layout enrolled on-chain (its length, or the challenge at the enrolled position), so none can be checked: the enrolled card answering differently than at setup, or a different card that signs another way. The operation ends at once (no retry on the sheet) | Tap the enrolled card again (on Send: send the same amount to the same account again within 10 minutes). If it repeats with the enrolled card, report it with the Log and a Card check report of that card |
| `No card was tapped. Try again and hold the card flat against the back of the phone.` | `AppViewModel.withCard` | No card within 2 minutes. Cards that aren't NFC-A or NFC-B are never detected at all | Tap within 2 minutes |
| `Card tap cancelled` | `AppViewModel.withCard` | `Cancel` on the tap sheet, also during the hold countdown | Nothing |
| `This card doesn't speak ISO-DEP. Transit cards, stickers and key fobs can't sign; use a contactless bank card.` | `IsoDepTransceiver` (`MainActivity.kt`) | Not an ISO 14443-4 card | Use a contactless bank card |
| `This wallet was set up with the old simulated demo card. Forget it in Settings and set up again with your bank card.` | `AppViewModel.withCard` (`Store.legacyDemoWallet`) | A wallet from a build that still had the demo card | Settings → `Forget this wallet on this phone`; create a new wallet |
| `the card didn't sign (<last problem>)` | `CardService.sign` | No signing method got a signature out of this card. With a card of another network it is usually `card answered SELECT <AID> with 6A82 (not found)` | Tap the enrolled card. If it is the enrolled card, run Card check on it and report both |
| `this isn't the card enrolled in the wallet` | `WrongCardException` | Never shown as such: `withCard` turns it into the hint above, and after three attempts into `That card isn't one of this wallet's cards. Use a card you added to this wallet.` | |
| `No card enrolled` | `AppViewModel.send` | The wallet has no card signer | Shouldn't happen; report it |
| `card challenge for proposal <n> was not sealed` | `TapvaultWallet.awaitCardChallenge` | The contract's card challenge didn't show up within 20 s (slow RPC or block) | On Send: send the same amount to the same account again (finishes the same payment, no prompt). Otherwise Activity → `Approve · card` |

The reasons inside `This card can't sign for Tapvault: …` and
`the card didn't sign (…)` are listed under
[Reasons in the report and in card errors](#reasons-in-the-report-and-in-card-errors).

## Phone key (fingerprint and screen lock)

The phone key is a P-256 key in the Android Keystore (TEE), alias
`tapvault_phone_p256`, created by `KeystoreSigner.create`. It needs a fresh
check for every signature: on Android 11+ a strong biometric or the screen
lock PIN/pattern/password; on Android 9/10 a fingerprint only (the prompt then
has a `Cancel` button).

| Message | From | Meaning | What to do |
|---|---|---|---|
| `On this Android version Tapvault needs a fingerprint. Add one in Settings → Security, then try again.` | `KeystoreSigner.create` | Android 9/10 without an enrolled fingerprint | Enrol a fingerprint; **Continue setup** |
| `Set up a screen lock (PIN, pattern or password) on this phone first, then continue. (<detail>)` | `AppViewModel.continueSetup` | Creating the key failed, usually because there is no secure screen lock | Set a screen lock; **Continue setup** |
| Another technical text during "Creating your phone key in secure hardware" | Android Keystore, passed through `continueSetup` | Key creation failed with an `IllegalStateException` from Android | Same as above; report the text |
| `Open Tapvault to approve with your fingerprint or screen lock.` | `KeystoreSigner.sign` | A prompt was due but the app wasn't on screen for 3 minutes | Keep the app open during approvals; try again |
| `Approval cancelled: <system text>` | `KeystoreSigner.sign` (`onAuthenticationError`) | The prompt was dismissed, timed out, locked out after too many attempts, or the screen went off. Nothing was signed or sent | Try again. After a lockout, unlock the phone with the PIN first |
| `Approval timed out` | `KeystoreSigner.sign` | The prompt stayed open over 3 minutes | Try again |
| `The phone didn't unlock its key. Try again.` | `KeystoreSigner.sign` | Authentication succeeded without a usable signature object | Try again; report if it repeats |
| `This phone's Tapvault key no longer works (the screen lock was removed). The wallet needs its recovery path; on testnet, forget it in Settings and create a new one.` | `KeystoreSigner.sign` (`KeyPermanentlyInvalidatedException`) | Android invalidated the key. With a single card the wallet can't be recovered | Report it. Settings → `Forget this wallet on this phone`; new wallet |
| `This phone's Tapvault key is missing. On testnet, forget the wallet in Settings and create a new one.` | `KeystoreSigner.publicKey`, `KeystoreSigner.sign` | There is no key under the alias. Send, Approve · card, Approve · phone, Cancel, Freeze with phone and Request unfreeze read the public key first, so they stop here before any prompt or card tap | Same as above |
| `This phone isn't a signer of the wallet` | `AppViewModel.send` | The phone's current key is not the wallet's phone signer (the key was recreated) | Same as above |
| `This phone's key isn't a signer of this wallet. Forget the wallet in Settings and set up again.` | `AppViewModel.phoneIndex` | The same cause, from Approve · card (before the tap), Approve · phone, Cancel, Freeze with phone and Request unfreeze | Same as above |
| Settings → `Phone key`: `software (no secure hardware!)` | `KeystoreSigner.hardwareLabel` | The key isn't in secure hardware | Report: the phone isn't suitable. Normal values: `Trusted Execution Environment` (Android 12+), `secure hardware` (older); `StrongBox secure chip` isn't expected (the key is created without StrongBox). `none` means there is no key; `unknown` means the key's details couldn't be read |

Face unlock: weak face unlock doesn't count as a strong biometric, so the
prompt offers the fingerprint or the screen lock instead.

## Security key

| Message | From | Meaning | What to do |
|---|---|---|---|
| `That isn't your security key. N tries left before a pause.` | `AppViewModel.requireSecurityKey` | Wrong key typed | Type it again. After 5 wrong tries in a row each try waits (30 s, doubling, at most 1 h) |
| `Too many wrong security keys. Try again in …, or reset it with your card: Settings → Security key.` | `requireSecurityKey` | The pause after wrong tries | Wait, or reset it |
| `Use at least 6 characters.` / `Pick something harder to guess.` / `That one is too easy to guess. Pick another.` / `The two entries don't match.` | `SecurityKey.problem` | A new key was refused | Pick another |
| `… Nothing was sent. Payment #N is saved and waits 10 minutes for your card: send the same amount again, or cancel it in Activity.` | `AppViewModel.send` | The key wasn't entered after the card tap | Send the same amount again to finish it (it reuses the request), or cancel it in Activity |
| `The card's signature didn't check out, so the security key wasn't reset. Tap it again.` | `resetSecurityKey` | The reset's card signature failed the check on the phone | Tap the card again; it must be one of this wallet's cards |
| `This wallet has no card yet, so a card can't confirm the reset. Add a card in Security first.` | `resetSecurityKey` | The wallet was made without a card (phone-only) or no card was added yet: the reset needs a tap of one of the wallet's cards | Add your card (Security → Add your card) and wait the 1 h; if the key is forgotten too, the security key can't be reset on this phone |

The key is asked by the app, not by the wallet contract. Forgetting it never
locks funds: a tap of one of the wallet's cards and the fingerprint set a new
one (Settings → Security key → Forgot it?, or `Forgot security key?` in the
prompt).

## Sending, Activity and freeze

| Message or state | From | Meaning | What to do |
|---|---|---|---|
| `Enter an amount like 0.5` | `AppViewModel.send` | Not a number, or more than 24 decimals. Rare: the `Review` button stays disabled until the amount is a number above zero | Fix the amount (a comma works as the decimal point) |
| `Amount must be more than zero` | `AppViewModel.send` | Rare, for the same reason | |
| `That isn't a NEAR account id` | `AppViewModel.send` | 2–64 characters of lowercase letters and digits, `-` or `_` only between them, parts separated by dots (`friend.testnet`), or a 64-hex implicit account | Fix the account id |
| `The wallet is frozen. Request an unfreeze first.` | `AppViewModel.send` | | Home → `Request unfreeze`, then Execute after 1 h |
| `That's this wallet's own account` | `AppViewModel.send` | | Use another receiver |
| `The wallet only has <x> NEAR available to send` | `AppViewModel.send` | Spendable is the balance minus the NEAR locked for the contract's storage (about 3.35). Home shows the spendable amount | Send less, or Home → Receive and top up from any testnet account |
| `<account> doesn't exist on NEAR testnet` | `AppViewModel.send` | The RPC says the named account doesn't exist (`NearClient.accountExists`). A network failure during this check shows the network error instead ([Network and RPC](#network-and-rpc)). 64-hex implicit accounts are always accepted (they exist once they receive NEAR) | Check the spelling and the `.testnet` suffix |
| `<message>` + `Payment #<n> is saved and waits 10 minutes for your card: …` | `AppViewModel.send` | The phone approved and the proposal exists, but the card step failed | Within 10 minutes, send the same amount to the same account again: no prompt, just the tap (the app reuses it while more than 30 s remain). Or Activity → `Approve · card`, or `Cancel` |
| `Approved, waiting` + `The wallet didn't treat this as within your limits (the phone's clock may be off). Execute it from Activity when it's ready.` | `AppViewModel.send` | The app judged the payment instant (phone clock), but the contract refused to execute it at once (by its own clock and allowance, or the wallet was frozen meanwhile). The card approval was then sent on its own, so the payment is approved and waits for the transfer delay (1 h) | Set date and time to automatic. Activity → `Execute` once it shows `Approved · ready: tap Execute` (an earlier Execute fails with `Delay has not elapsed`), or `Cancel` |
| `no proposal <n>` | `TapvaultWallet.approvePhone`, `cancelByPhone` | Already executed, cancelled or cleaned up | Wait for Activity to refresh |
| `Waiting for approvals` | `Screens.kt` `proposalStatus` | Not fully approved | `Approve · card` while it is offered (up to 10 minutes after the challenge was sealed); afterwards only `Cancel`, or `Clean up` after a day |
| `Approved · can execute in <h> h <m> min` | `proposalStatus` | Approved; the delay runs (from full approval) | Wait; nothing executes by itself |
| `Approved · ready: tap Execute` | `proposalStatus` | The delay has passed by the phone's clock | `Execute` (no prompt, no tap) |
| `Approved · wallet is frozen` | `proposalStatus` | A ready transfer while frozen; no Execute button | Unfreeze first. Executing the unfreeze voids it |
| `Void: signers changed` | `proposalStatus` | A settings change (for example the unfreeze) executed after it was made | `Clean up`, then send again |
| `Expired: it can no longer execute; cancel it` | `proposalStatus` | More than 7 days (the contract's `EXECUTION_WINDOW`) have passed since its delay ended, by the phone's clock. No `Execute` button | `Cancel` it, or `Clean up` once offered (the longest delay plus 7 days after full approval: 8 days in app wallets); send again if still wanted |
| No `Execute` button | `ActivityScreen` | Shown only when the delay has passed by the phone's clock, not for transfers while frozen, and not for expired entries | Set date and time to automatic |
| No `Approve · card` button | `ActivityScreen` | The card window is over, the card already approved, or the entry is void | `Cancel` and send again |
| `Send` and the snowflake dimmed on Home | `Screens.kt` `Home` | Frozen, busy, or the wallet hasn't loaded since the app started (needs the network once) | Wait for the balance to show; unfreeze if frozen |
| `Clean up` shows no result banner | `AppViewModel.closeStale` | Normal; the entry disappears on the next refresh | Nothing |

## Backup cards, lost cards, recovery and Security Center

| Message or state | From | Meaning | What to do |
|---|---|---|---|
| `That card is already one of this wallet's cards. Hold a different card to add it as a backup.` | `AppViewModel.readBackupCard` (`AlreadySignerException`) | The tapped card's key hash is already a signer; it signed nothing | Hold the new card |
| `This card can't sign for Tapvault: <reason>. Try another contactless card.` | `AppViewModel.readBackupCard` | The new card failed the same checks as at setup ([reasons](#reasons-in-the-report-and-in-card-errors)) | Another card; Card check shows why |
| "No card yet" in Security, "Phone only · add your card" on Home | `Security.kt`, `Screens.kt` (`WalletState.phoneOnly`) | The wallet was made *Without a card for now*: threshold 1, the phone key alone approves | **Add your card**: hold the card, approve with the fingerprint (no other card taps), then after 1 h tap Execute in Activity. The change also sets the approvals needed to 2 |
| "Add your card first" on Add a guardian phone | `Security.kt` | A phone-only wallet can't get a second signer without its card: a guardian would sign alone at threshold 1 | Add the card first |
| `This wallet's code can't check the card. Read the card again.` | `AppViewModel.addFirstCard` | The card read has no possession proof (an older contract). Not expected: phone-only wallets run v0.3 | Read the card again; report it |
| `The card's test signature didn't check out, so it won't be added. Tap it again, or use another card.` | `AppViewModel.readBackupCard` (`provesPossession`) | The new card's signature over the test challenge didn't verify on the phone. v0.1 contracts don't check this for cards added after setup (v0.2 does), so the app refuses the card | Tap again; if it repeats, report the card's Card check report |
| `This wallet already has 8 signers, the most it can have.` | `AppViewModel.readBackupCard` | `MAX_SIGNERS` is 8 | Nothing to add |
| `<card> is already a signer of this wallet.` | `AppViewModel.addBackupCard` | The card was added meanwhile | Nothing |
| `<message>` + `Request #<n> is saved: approve it with your card in Activity within 10 minutes, or cancel it there.` | `AppViewModel.addBackupCard` | The phone proposed adding the card, but the approving card tap failed | Activity → `Approve · card` with a card already in the wallet, or Security Center → `Add backup card` again with the same new card (it finishes the same request) |
| `Backup card added in 1 h` | `AppViewModel.addBackupCard` | Proposed and approved; it becomes a signer only when executed after the config delay | After 1 h: Activity → `Add backup card` → `Execute`. Payments still waiting then become `Void: signers changed` |
| Home pill `No backup card · add one` (amber) | `Screens.kt` `securityPill` | The wallet has one card | Tap it → Security Center → `Add backup card` |
| Home pill `Unknown request · review` (red) | `securityPill` | An open request that this phone didn't approve: it was started with a card | Activity: cancel it if you didn't start it, and consider freezing |
| Security Center card `not read on this phone yet` | `SecurityScreen` | A card signer this phone has no record of (network and last digits aren't on chain) | Nothing; it still signs (the app tries the usual methods) |
| Screenshots or screen recordings come out black | `MainActivity` (`FLAG_SECURE`) | On purpose: wallet screens never appear in screenshots, recordings, casts or the recent-apps preview | Photograph the screen with another device |
| `That's <card>, the card you're removing. Tap another card of this wallet.` | `AppViewModel.removeCard` | During "I lost this card" the card being removed was tapped to approve; it signed nothing | Hold another card of the wallet |
| `<card> is this wallet's only card. Add a backup card first; then you can remove this one.` | `AppViewModel.removeCard` | Removing it would leave no card | Freeze if it was stolen, add a backup card, then remove it |
| `Removing <card> would leave fewer signers than the <n> approvals needed.` | `AppViewModel.removeCard` | The contract would refuse it (`ERR_INVALID_THRESHOLD`) | Add another card first |
| `<message>` + `Request #<n> is saved: approve it with your other card in Activity within 10 minutes, or cancel it there.` | `AppViewModel.removeCard` | The removal was proposed, the approving tap failed | Activity → `Approve · card` with the other card |
| `<card> removed in 1 h` | `AppViewModel.removeCard` | Proposed and approved | After 1 h: Activity → `Execute`. If the wallet was frozen, the same change unfreezes it |
| `Wallet check failed` (banner, Home pill red, Security Center notice) | `AppViewModel.checkIntegrity`, `integrityNow` (`Integrity.evaluate`) | One of: **Wallet code** (the account's code hash is neither this APK's contract nor the previous build), **Account keys** (the account has an access key: anyone holding it can bypass the contract), **Phone key** (this phone's key isn't a signer, or is missing from the Keystore), **Setup** (still in setup mode) | If you didn't change anything: Security Center → `Freeze with this phone`, then report it with the Log. Checks are re-run at most every 6 h, and on **Check now** |
| Security Center check `Wallet code` shows a note "older build" | `Integrity.evaluate` | The wallet runs `GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow` (created before 12 Nov): still the official code, it just rejects Visa online fDDA signatures | Nothing |
| `The network's copy of request #<n> doesn't match what it says it does, so the phone won't sign it. …` | `ProposalInfo.verifiedAction` (Approve · phone) | The node's `get_proposal` answer has an action whose hash isn't the request's intent hash: a broken or dishonest RPC | Pull to refresh and retry (the app rotates RPCs); if it repeats, report it with the Log |
| `This request's action can't be read by this app version, so it won't be signed (…)` | `ProposalInfo.verifiedAction` | A request with an action this app doesn't know | Approve is refused; **Cancel** still works (its prompt says "its details can't be checked"). Freeze if you don't recognise it, and report it |
| "Tapvault is locked" | `LockOverlay` (`AppViewModel.lockIfAway`) | App lock: the app was away more than a minute or just started | Unlock with fingerprint or screen lock. It hides the screen only; funds are protected by the card + phone key either way |
| No watchtower notifications | `Watchtower.kt` | Alerts are off (Security Center → Checks → Alerts), notifications are denied (Android 13+), or Android delayed the job (Doze, battery saver: 15 min is a minimum, not a promise). Alerts only fire for what's new: a request this phone didn't approve, a freeze, a signer change, a failed check | Turn Alerts on; allow notifications; exempt Tapvault from battery optimisation |
| `That's the card you already added. Tap Continue setup and hold a different card, or skip the backup card.` | `AppViewModel.continueSetup` (backup card) | During setup with **Backup card** On, card 1 was tapped again; it signed nothing | Hold the second card, or **Skip the backup card** |
| `This card can't sign for Tapvault: <reason>. Tap Continue setup to try another card, or skip the backup card.` | `AppViewModel.continueSetup` (backup card) | The second card failed the card checks | Another card, or skip; add one later in Security Center |
| `Fingerprint only needs a fingerprint enrolled on this phone. …` | `KeystoreSigner.create(strict)` | Strict mode without a strong fingerprint | Enrol a fingerprint, or turn Fingerprint only off |
| `Add a backup card first: if your fingerprint stops working, two cards are how you get back in.` | `AppViewModel.switchToStrictKey` | Switching an existing wallet to fingerprint only needs two cards | Add a backup card |
| `Key switch in 1 h` / row **Pending** | `switchToStrictKey` | The new fingerprint-only key replaces the old one when the change executes | After the wait: Activity → Execute. The next refresh switches keys (log: `switched to the fingerprint-only phone key`) |
| `No Tapvault wallet found at <account>. …` | `findWalletToRecover`, `becomeGuardian` | No contract at that account, a typo, or no connection | Copy the account from the old phone's Receive screen or an explorer link |
| `Recovery needs <n> of this wallet's cards to approve, and it has <m>. …` / `Can't recover with cards alone` | `Recovery.plan` | Cards alone can't reach the threshold (one card, or lost cards marked) | A wallet with one card can only be recovered from a phone that is still a signer (or a guardian, after a card is added) |
| `That card is marked lost. Tap another card.` / `That card already approved. Tap another of the wallet's cards.` | `startRecovery`, `approveRecovery` | The tapped card can't count; it signed nothing | Hold the next card |
| `The recovery request is gone: a phone or card of the wallet cancelled it.` / `No open recovery request` | `approveRecovery`, `RecoverScreen` | The old phone (or a card the request doesn't remove) cancelled it | If it wasn't you, someone has the old phone: start again with the lost card marked |
| `Recovery requested` · `Joins in …` | `RecoverScreen` | Approved by cards; waits the recovery delay (24 h) | Keep the app installed; **Finish recovery** when the time is up |
| Notification `Someone is recovering this wallet` | `Watch.check` (old phone) | Cards proposed adding a new phone and removing this one | If it isn't you: Activity → Cancel, and freeze |
| `That guardian code has a typo or was cut off.` / `… doesn't hold a valid phone key.` / `… signature doesn't check out.` / `That guardian code is for <wallet>, not this wallet.` | `Guardian.decode`, `addAction` | The code was changed, cut, signed by another key, or made for another wallet | Make a new code on the guardian phone and copy it whole |
| `Not added yet` (guardian phone) | `checkGuardianAdded` | The owner's request hasn't executed | The owner: Activity → Execute after the settings wait |
| `The network's card challenge for request #<n> doesn't match its own seed. Nothing was signed; try another connection.` | `TapvaultWallet.checkedChallenge` (v0.2 wallets) | The node's proposal view is inconsistent: a broken or dishonest RPC | Retry (RPCs rotate); report it with the Log if it repeats |
| `A key this proposal adds hasn't proved possession (prove_possession)` | contract v0.2 `execute` | An `AddSigner` request without the new key's possession proof (an older app, or a script) | Propose it again from this app: it sends the proof |
| `Enter a NEAR account, like bob.testnet` | `AppViewModel.changeAllowlist` | The receiver typed in Instant receivers isn't a valid account name | Type the full account (`name.testnet`) |
| `This wallet runs an older contract without an allowlist. …` | `AppViewModel.changeAllowlist` | The wallet was created before v0.2 (invariant 4: it keeps its code) | Nothing; the row only shows on v0.2 wallets |
| `<receiver> is already on the list.` / `<receiver> isn't on the list.` / `The list is full (16 receivers).` | `AppViewModel.changeAllowlist` | The change would do nothing, or the contract's limit (`MAX_ALLOWLIST`) is reached | Pull to refresh; remove one first when full |
| A payment that was instant now waits the transfer delay | Contract v0.2 allowlist | The wallet has at least one instant receiver, and this receiver isn't on the list | Expected. Add the receiver (it takes the settings delay), or wait and execute |

## Network and RPC

The app talks to three public testnet RPCs in this order, starting from the one
that last answered: `rpc.testnet.fastnear.com`, `test.rpc.fastnear.com`,
`rpc.testnet.near.org`. Each call is tried up to 6 times across them with
growing pauses (0.4 s up to 3 s). Timeouts: 15 s to connect, 60 s per read,
90 s for sending a transaction, 120 s for the faucet. A signed transaction is
never signed again with a new nonce unless the chain rejected it before
running it (the same signed bytes may go to another RPC, but NEAR runs a
transaction at most once); after a timeout the app looks it up by hash for
about 2 minutes instead of sending it again.

| Message | From | Meaning | What to do |
|---|---|---|---|
| `can't reach <host>: <detail>` | `NearClient.rpc` | No connection to any RPC (the host is the last one tried) | Check the internet. If it happened after a card tap, look in Activity before retrying |
| `HTTP 429 from <url>: …`, `HTTP 408 …`, `HTTP 5xx …` | `NearClient.post`, `rpc` | Every RPC was rate-limiting or failing through all retries | Wait a minute; try again |
| `HTTP <code> from <url>: <body>` (other codes) | `NearClient.post` | A refusal that isn't worth retrying, for example the faucet rejecting a request | Read the body; see [Faucet and sponsor funding](#faucet-and-sponsor-funding) |
| `<method>: no RPC answered` | `NearClient.rpc` | Fallback text; not reached in practice, because the last RPC's error is shown instead | Try again later |
| `<method>: <CAUSE> <details>` | `NearClient.rpc` | A JSON-RPC error, for example `query: UNKNOWN_ACCOUNT …` (the account doesn't exist) | Depends on the cause |
| `<view> on <account>: <error>` | `NearClient.viewFunction` | A contract view failed, for example `get_wallet on tv-….testnet: … CodeDoesNotExist …` (no contract on that account) | Expected during setup before the deploy (handled); elsewhere report it |
| `<view> on <account>: no result` | `NearClient.viewFunction` | Unexpected RPC answer | Try again |
| `<account> has no access key ed25519:<…>: <RPC text>` | `NearClient.nonce` | The key a transaction is signed with isn't on that account (some RPCs answer this way; others give `query: UNKNOWN_ACCESS_KEY …`). In practice a wrong sponsor key, see [Faucet and sponsor funding](#faucet-and-sponsor-funding) | Fix that key; otherwise report it with the Log |
| `no nonce for <account>` | `NearClient.nonce` | The RPC answered the access-key lookup without a nonce | Try again; report it if it repeats |
| `The network refused the transaction: <reason>` | `NearClient.send` | Rejected before running (`INVALID_TRANSACTION`). Usually `Not enough NEAR in the account`: the signer (after setup, the fee account) can't pay the gas | Top up the fee account; see [Faucet and sponsor funding](#faucet-and-sponsor-funding) |
| `The network didn't confirm transaction <hash> in time. It may still go through: check Activity (or https://testnet.nearblocks.io/txns/<hash>) before trying again.` | `NearClient.awaitOutcome` | The transaction was (or may have been) delivered, but no result came within about 2 minutes. The app never signed a second copy, so it can run at most once | Open the link or Activity. Retry only if it didn't land. During setup, **Continue setup** checks the chain first |
| A `send_tx: INVALID_TRANSACTION …InvalidNonce…` text | `NearClient.send` | Three nonce conflicts in a row (the fallback text `transaction could not be sent` is not reached in practice) | Try again |
| `Can't reach NEAR testnet right now. Showing the last known state; retrying.` | `AppViewModel.refresh` (Home banner) | The background refresh failed | Wait; it clears on the next successful refresh |
| `Can't reach NEAR testnet right now (<detail>). Try Continue setup again.` | `AppViewModel.continueSetup` | See [Setup](#setup) | |
| `Not enough NEAR: the account must keep enough to pay for its storage` | `NearClient.readableError` (`LackBalanceForState`) | An account would drop below its storage reserve | Top up that account |
| `Not enough NEAR in the account` | `NearClient.readableError` (`NotEnoughBalance`, `BalanceExceeded`) | The account can't pay the amount or the gas | Top up that account |
| `That account doesn't exist on NEAR testnet` | `NearClient.readableError` (`AccountDoesNotExist`) | A receipt went to a missing account | Check the receiver |
| `That account already exists` | `NearClient.readableError` (`AccountAlreadyExists`) | Account creation hit an existing name | **Continue setup** (accounts holding this setup's key are reused) |
| `This key can't sign for the account any more` | `NearClient.readableError` (`AccessKeyNotFound`, `InvalidAccessKeyError`) | The chain rejected the access key the transaction was signed with (it isn't, or is no longer, on the account). A key that was never on the account usually fails earlier, at the nonce lookup (`<account> has no access key …`; see the wrong-sponsor-key row under [Faucet and sponsor funding](#faucet-and-sponsor-funding)) | Report it with the Log |
| Up to 300 characters of raw JSON | `NearClient.readableError` | A failure it doesn't recognise | Report it |
| Busy overlay for minutes | `NearClient` retries | All three RPCs are slow or unreachable. There is no cancel button | Wait for the result; don't kill the app in the middle of a send |

## Contract errors

The contract panics with one of these strings (`contract/src/error.rs`); a
panic reverts everything that call did. The app shows the bare string; the
explorer shows `Smart contract panicked: <string>`. Many can't be reached from
the app (it never makes those calls), but they are listed for completeness.

A Send within the limits (and `Approve · card` in Activity on a payment the
phone approved, when it is within the limits) puts `approve_card` and
`execute` in **one** transaction: if `execute` fails, the card approval is
undone with it. When the failure is `Delay has not elapsed`, `Not enough
approvals` or `Wallet is frozen`, the app then sends the same card approval on
its own (Send shows `Approved, waiting`; for Activity see `Signer already
approved` below); after any other failure the card approval is gone. "Within
the limits" is judged with the phone's clock (`LimitInfo.isInstant`).

| Message | Constant | In the app, typically when | What to do |
|---|---|---|---|
| `Wallet is still in setup` | `ERR_IN_SETUP` | Never in normal use: Home is shown only after `setup_finalize` landed | Check `get_wallet` ([TESTING.md](TESTING.md#4-check-the-wallet-on-the-explorer)); report |
| `Wallet setup is already finalized` | `ERR_NOT_IN_SETUP` | A setup step sent again after it had landed | **Continue setup**: it re-reads the chain and skips finished steps |
| `Wallet is frozen` | `ERR_FROZEN` | A transfer proposed or executed while frozen, an `execute_upgrade` while frozen (v0.2), or freezing twice, when the screen was out of date | Wait for Home to refresh; `Request unfreeze` |
| `Wallet is not frozen` | `ERR_NOT_FROZEN` | Executing an unfreeze when the wallet isn't frozen | Nothing; `Cancel` or `Clean up` it |
| `Too many signers` | `ERR_TOO_MANY_SIGNERS` | Not reachable (the app adds one card; the maximum is 8 signers) | |
| `Signer already enrolled` | `ERR_DUPLICATE_SIGNER` | Adding the card again after it was added | **Continue setup** (it sees the card on-chain and skips) |
| `Invalid signer key` | `ERR_INVALID_SIGNER_KEY` | Shows as `adding the card failed: Invalid signer key`: the card's key is outside the contract's rules (modulus 64–248 bytes, odd, no leading zero; exponent 3 or 65537; UN 4–8 bytes inside at most 64 bytes of terminal data). Not expected: the card step checks these rules on the phone first (the key before any signing attempt, refused with `this card can't be a signer: …`) | This card can't be used. Send its report and the Log; **Start over** with another card |
| `Signer is the wrong kind for this operation` | `ERR_WRONG_SIGNER_KIND` | Not reachable | Report |
| `Signer index out of range` | `ERR_SIGNER_INDEX` | Not reachable | Report |
| `P-256 signature invalid` | `ERR_P256_INVALID` | The phone's signature doesn't match the message the contract computed: a different key, or another proposal landed between reading `next_proposal_id` and proposing | Try once more; if it repeats, report with the Log |
| `WebAuthn signer requires an assertion payload` | `ERR_MISSING_WEBAUTHN` | Not reachable (no passkeys in the app) | |
| `WebAuthn assertion invalid` | `ERR_WEBAUTHN_INVALID` | Not reachable | |
| `Card signature verification failed` | `ERR_CARD_INVALID` | The card's signature didn't verify on-chain for this challenge and layout. The app verifies each signature on the phone first, so on a new wallet this points to an app/contract mismatch. Wallets created before 12 Nov (code hash `GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow`) reject Visa online signatures (format 95). A replayed (skimmed) signature also gets this | Check the wallet's code hash; an old wallet needs a new one. Otherwise report with the Log, the card report and the wallet account |
| `Invalid threshold` | `ERR_INVALID_THRESHOLD` | `finalize failed: Invalid threshold`: the threshold doesn't fit the signers (2 needs a card on chain; 1 only with the phone alone). The app picks it from the signers on chain, so this isn't expected. On `execute`: a change that adds a key to a phone-only wallet without `SetThreshold(2)`, or lowers a two-key wallet to 1 | Report with the Log and the wallet account |
| `At least one P-256 (phone / security key) signer is required` | `ERR_NO_DELIBERATE_SIGNER` | Not reachable | |
| `Invalid delays` | `ERR_INVALID_DELAYS` | Not reachable (the app uses 1 h / 1 h / 24 h, the shortest allowed) | |
| `Invalid limits` | `ERR_INVALID_LIMITS` | "Instant payment limit" above "Daily limit" (the app checks this first) | Report |
| `Invalid action` | `ERR_INVALID_ACTION` | A zero amount (the app checks first) | Report |
| `Proposal rate limit reached for today` | `ERR_RATE_LIMITED` | The phone made 24 proposals in the current 24-hour window. Every new Send and every Request unfreeze counts; finishing an unfinished payment doesn't | Wait until 24 h after the window began (its first proposal). Executing a settings change, such as an unfreeze, also resets the count |
| `Proposal not found` | `ERR_NO_PROPOSAL` | Already executed, cancelled or cleaned up; the screen was out of date | Wait for Activity to refresh |
| `Signer set changed since this proposal was created` | `ERR_STALE_PROPOSAL` | A settings change (the unfreeze) executed after this proposal; it can never execute (`Void: signers changed`) | `Clean up`; make a new one |
| `Signer already approved` | `ERR_ALREADY_APPROVED` | Approving twice (double tap, out-of-date screen). Also after `Approve · card` in Activity on a payment the app judged instant but the contract wouldn't execute yet: the app sends the card approval on its own, which lands, then sends it a second time, which gets this error | Refresh: the entry shows the card approval; `Execute` when ready |
| `Card approval window has closed; propose again` | `ERR_CHALLENGE_EXPIRED` | The card approved more than 10 minutes after the challenge was sealed (`CARD_APPROVAL_WINDOW` = 600 s) | `Cancel` it and send again |
| `This signer has too many open proposals; cancel or wait for some` | `ERR_TOO_MANY_OPEN` | The phone has 4 proposals that can still complete (`MAX_OPEN_PER_SIGNER`): approved but not executed, or unapproved and under about 11 minutes old | `Execute` or `Cancel` some in Activity, or wait 11 minutes for unapproved ones to lapse |
| `Only the wallet account itself can initialise it` | `ERR_INIT_NOT_SELF` | Not reachable (the app deploys and initialises from the wallet account) | |
| `setup_finalize must be sent by the wallet account itself, so its key is deleted` | `ERR_FINALIZE_SIGNER` | Not reachable | |
| `Card challenge not sealed yet; retry in the next block` | `ERR_CHALLENGE_NOT_READY` | Not reachable (the app waits for the challenge) | |
| `Not enough approvals` | `ERR_NOT_ENOUGH_APPROVALS` | `Execute` before both phone and card approved | Approve with the missing signer first |
| `A transfer needs at least one phone / security-key approval` | `ERR_NEEDS_DELIBERATE` | Not reachable (threshold 2 always includes the phone) | |
| `Delay has not elapsed` | `ERR_TOO_EARLY` | `Execute` before the delay ended (phone clock ahead). A Send the app treated as instant but the contract didn't shows `Approved, waiting` instead ([Sending, Activity and freeze](#sending-activity-and-freeze)) | Set date and time to automatic; wait for `Approved · ready: tap Execute` |
| `Proposal expired` | `ERR_EXPIRED` | Executing more than 7 days (`EXECUTION_WINDOW`) after the delay ended. The app shows such an entry as `Expired: it can no longer execute; cancel it` without `Execute`, so this means the phone's clock is behind | `Cancel` it, or `Clean up` once offered; send again |
| `Proposal is not stale or expired` | `ERR_NOT_CLOSABLE` | `Clean up` too early (phone clock ahead) | Wait |
| `Signer not found` | `ERR_SIGNER_NOT_FOUND` | Not reachable (the app never removes signers) | |
| `Limit not found` | `ERR_LIMIT_NOT_FOUND` | Not reachable | |
| `This signer may not cancel a proposal that removes it` | `ERR_CANNOT_CANCEL` | Not reachable (the app cancels with the phone) | |

Other runtime texts can appear the same way (for example
`Exceeded the prepaid gas.`); report them with the Log.

## Reading the Card check report

Card check (`CardService.inspect`) runs exactly what enrolment runs, with a
random challenge and without touching the chain: select the payment
application (up to five are tried), GET PROCESSING OPTIONS, read the records,
recover the card's key through its certificates, check it against the
contract's rules for a card key, then try signing methods until one produces a
signature that verifies. More on the card protocol:
[CARDS.md](CARDS.md).

### On screen

| Row | Meaning |
|---|---|
| `<network> •••• <last 4>` | Network from the AID's first 5 bytes (`Visa`, `Mastercard`, `RuPay`, else `unknown`) and the last four digits |
| Line under it | The application label (tag `50`) or the AID |
| Badge `Can sign` / `Can't sign` | Whether any method gave a signature that verified with the card's certified key |
| `Offline auth` | Which of `SDA`, `DDA` and `CDA` the AIP announces, joined with ` · ` (for example `SDA · DDA`), or `none`. Visa fDDA cards usually show `DDA` |
| `CA key index` | Tag `8F`: which network CA key certifies the issuer |
| `Issuer key` | Issuer key size; `–` if the issuer certificate couldn't be recovered |
| `Card key` | The card's own RSA key: bits and exponent; `–` if not recovered |
| `Signs with` | `INTERNAL AUTHENTICATE (DDA)`, `signature in GET PROCESSING OPTIONS (fDDA)` or `signature in GENERATE AC (CDA)`; `–` if none worked |
| `Challenge format` | The card's DDOL (tag `9F49`) or `default 9F3704`. Only DDA uses it |
| Notes (`· …`) | Verdict and problems, see below |

### The shared text

**Share report** on the Card check screen (**Share card report** on the Setup
screen) sends `CardReport.diagnostics()`:

```text
network: <network> (<AID>, "<label>")
AIP: <hex>  SDA=<true|false> DDA=<true|false> CDA=<true|false>
CA index: <hex or ->  issuer key: <bits or -> bit
card key: <bits or -> bit, e=<exponent or ->  DDOL: <hex or default 9F3704>
card: ****<last 4>
signs with: <method, or "nothing that worked">
- <note>
attempts:
  <METHOD> <AID>[ amount 100][ TC][ offline-ok]: <result>
trace (card number, name, track data and certificates left out):
  <COMMAND> -> <status word> <answer summary>
```

- `attempts`: one line per signing try, in order. `amount 100` means the card
  was shown 1.00 INR (never charged); `TC` asked for an offline approval
  instead of an online cryptogram; `offline-ok` allowed the card to approve
  offline (TTQ `37004000` instead of `37804000`). The result is
  `signature OK (format <05|95>, <n> bytes of terminal data)` or why it failed.
- `trace`: every command and the card's status word. In the answer summary,
  `TAG(length)` hides the value; values are shown only for tags that can't
  identify the card or its holder (`TAG=HEX`, labels as `TAG="text"`).
  `(<n> bytes, not TLV)` means the answer couldn't be parsed.
- The report never contains the full card number, the name, the expiry date,
  track data or the certificates. If you ever see them, report it.

### What to look for

| Note or result | Meaning | Next step |
|---|---|---|
| `signs with <method>: this card can be a Tapvault signer` | Good | Use it for the wallet |
| Visa: `FDDA <AID>: signature OK (format 95, 19 bytes of terminal data)` | The expected Visa result (online fDDA); not yet seen on a real card | Good |
| `CDA <AID>: GENERATE AC answered without a signature (declined: AAC)` then `CDA <AID> amount 100: signature OK (format 05, 4 bytes of terminal data)` | The card declined a zero amount, then signed at 1.00 INR. This is how the simulated Mastercard behaves; a real one may sign at zero straight away | Good |
| `DDA <AID>: card answered INTERNAL AUTHENTICATE with 6D00 (command not supported)` | Many contactless cards don't do DDA; other methods are tried next | Look at the later attempts |
| `can't be a Tapvault signer: no CA key for <RID> index <XX>: this app doesn't know that network key yet; share the card report so it can be added` | `android/core/src/main/resources/capk.txt` has no CA key with that RID and index. Most likely for RuPay (NPCI doesn't publish its keys) and for other networks (`unknown`) | Share the report. A developer adds the key (RID, index, exponent, modulus from an authoritative list, checksum verified) |
| `issuer certificate did not verify against CA <XX>` | A key with that index exists but doesn't recover the issuer certificate (a wrong or test key under that index, or an unusual card) | Share the report |
| `card has no CA key index (no offline data authentication)` | The card doesn't do offline authentication at all | This card can't be a signer |
| `no card certificate (the card has no key of its own)` | The card has no RSA key of its own (SDA only) | This card can't be a signer |
| `can't be a Tapvault signer: its key is <n> bits; the wallet accepts 512 to 1984` (or `… its key exponent is <e>; the wallet accepts 3 or 65537`, `… its key isn't a valid RSA modulus`) | The card's own key is outside what the contract accepts. Found before any signing attempt, so that application has no attempts | This card can't be a signer |
| `card certificate hash mismatch (static data)` | The certificate didn't match the data the app hashed | Share the report: likely an app gap |
| `signature didn't verify with the card's certified key` (in attempts) | The card signed, but the app rebuilt different signed data | Share the report: likely an app gap |
| `it wouldn't sign a challenge with any method` | The key was recovered, but every attempt failed; the attempts say why | Share the report |
| `issuer certificate expired <MM>/<YY>` | Informational | None |
| `no PPSE (…); trying known applications` (or `unreadable PPSE (…); …`) | No usable contactless directory. The app falls back to its list of well-known AIDs (`EmvSession.KNOWN_AIDS`) and tries all five in order: `A0000000031010` (Visa), `A0000000041010` (Mastercard), `A0000005241010` (RuPay), `A0000000043060` (Maestro, shown as `Mastercard`), `A0000000032010` (Visa Electron, shown as `Visa`) | Fine if a later step worked |
| `<AID>: <reason>` | That application failed; the next one (up to five) was tried | See the reasons below |

### Reasons in the report and in card errors

These texts appear in the notes, in the attempts, inside
`This card can't sign for Tapvault: …` (setup) and inside
`the card didn't sign (…)` (payments). They
come from `EmvSession`, `Rsa` and `CardService` in `Emv.kt`.

| Reason | Meaning |
|---|---|
| `card answered <command> with <SW> (<meaning>)` | The card refused a command; status words below. `<command>` is `SELECT <AID>`, `GPO`, `READ RECORD <sfi>.<record>`, `INTERNAL AUTHENTICATE`, `GENERATE AC (ARQC, CDA)` or `GENERATE AC (TC, CDA)`. A refused `SELECT PPSE` becomes the `no PPSE (…)` note, and a refused `READ RECORD <sfi>.<record> (fresh)` (the fDDA re-read) is ignored; both still show in the trace |
| `short response to <command>` | The card answered with less than a status word (`<command>` can also be `GET RESPONSE`) |
| `GPO response too short`, `GPO response has no AIP` | Unusual GET PROCESSING OPTIONS answer |
| `no issuer certificate`, `no issuer exponent`, `no PAN record` | Records needed for the certificate chain are missing |
| `no CA key for …`, `issuer certificate did not verify against CA <XX>` | See the table above |
| `no card certificate (the card has no key of its own)`, `no card exponent` | No card key |
| `card certificate format`, `card certificate is for a different card number`, `card key length mismatch`, `card certificate hash mismatch (static data)`, `card key exponent too large` | The card certificate didn't check out |
| `this card can't be a signer: its key is <n> bits; the wallet accepts 512 to 1984`, `… its key isn't a valid RSA modulus`, `… its key exponent is <e>; the wallet accepts 3 or 65537` | The card's own key breaks the contract's rules for a card signer (`EmvKeyLayout::validate`: 512 to 1984 bits, no leading zero byte, odd, exponent 3 or 65537). Checked right after the key is recovered, before the card is asked to sign. In the notes it reads `can't be a Tapvault signer: its key …` |
| `certificate check failed` | An internal certificate check failed |
| `signature length <n> != modulus <m>`, `signature not reduced` | Malformed signature or certificate data |
| `no signature in the GPO answer (<cryptogram>)` | fDDA attempt: the card didn't sign inside GET PROCESSING OPTIONS |
| `the card's PDOL has no Unpredictable Number` | fDDA isn't possible on this card |
| `the card's DDOL has no Unpredictable Number`, `INTERNAL AUTHENTICATE returned no signature` | DDA isn't possible |
| `no CDOL1, so no GENERATE AC`, `CDOL1 has no Unpredictable Number` | CDA isn't possible |
| `GENERATE AC answered without a signature (<cryptogram>)` | CDA attempt without a signature, usually a decline |
| `unreadable answer (<detail>)`, `the card's answer couldn't be read` | An answer wasn't valid TLV (`truncated tag`, `truncated length`, `bad length`, `value overruns buffer`), or a data object list from the card (PDOL, CDOL1 or DDOL) was cut short (`truncated DOL tag`, `truncated DOL length`). Inside `the card didn't sign (…)` it is just `unreadable answer` |
| `the card has no payment application` | Fallback text; not reached in practice, because without a PPSE the app still tries the well-known AIDs and reports why they failed |
| `this card won't sign a challenge over NFC` | Setup's summary when no method worked |

`<cryptogram>` is `declined: AAC`, `approved offline: TC`, `online: ARQC`,
`CID <XX>` or `no cryptogram`.

Status words (`swMeaning`):

| SW | Text in the message |
|---|---|
| `6283` | application blocked |
| `6700` | wrong length |
| `6981` | command incompatible |
| `6984` | data invalid |
| `6985` | conditions of use not satisfied |
| `6986` | command not allowed |
| `6A81` | function not supported |
| `6A82` | not found |
| `6A83` | record not found |
| `6A86` | wrong P1/P2 |
| `6A88` | data not found |
| `6D00` | command not supported |
| `6E00` | class not supported |
| `6F00` | card error |
| `63xx` | warning |
| anything else | error |

## Faucet and sponsor funding

Each wallet uses two accounts: the wallet `tv-<10 hex>.testnet` and its fee
account `tv-<10 hex>-gas.testnet` (or sub-accounts `tv-<10 hex>.<sponsor>` and
`tv-<10 hex>-gas.<sponsor>`). Each comes from the faucet while it cooperates,
otherwise from the sponsor (`Onboarding.createAccounts`).

**Faucet.** `https://helper.nearprotocol.com/account` creates a `.testnet`
account with 10 NEAR. It is rate-limited per IP, about 10 accounts per 12 h,
then answers HTTP 429. One wallet takes two accounts, and so does each run of
`TestnetE2ETest`.

| Problem | Message | What to do |
|---|---|---|
| Faucet rate limit, no sponsor in the APK | `The testnet faucet refused (HTTP 429 from https://helper.nearprotocol.com/account: …). Try again later or from another network.` | Wait, or switch network (Wi-Fi ↔ mobile data changes the IP), then **Continue setup**; or use an APK built with a sponsor |
| Faucet error | `The testnet faucet refused (<reason>). Try again later or from another network.` with an HTTP error or a connection error as the reason | Same |
| Faucet slow | `The testnet faucet refused (faucet did not create <account>). Try again later or from another network.` (with a sponsor built in, the sponsor creates the account instead) | **Continue setup** (same names and keys; an account the faucet made late is reused) |

**Sponsor.** If `android/local.properties` contains `tapvault.sponsor.account`
and `tapvault.sponsor.key` when the APK is built, they are compiled into
`BuildConfig` and the app creates sub-accounts of that account whenever the
faucet refuses: 5.5 NEAR for the wallet (about 3.35 is locked for the contract's
storage, about 2.1 is spendable) and 0.5 NEAR for the fee account, 6 NEAR per
wallet. The project's sponsor is `tapvault-skyyy.testnet` (about 51 NEAR on
12 Nov, so roughly 8 wallets). Anyone holding such an APK can spend the
sponsor's testnet NEAR; build without those two lines to leave it out, and
never commit `local.properties`.

| Problem | Message | What to do |
|---|---|---|
| Sponsor out of NEAR | `The network refused the transaction: Not enough NEAR in the account`, or `creating <account> failed: Not enough NEAR …` | Send testnet NEAR to the sponsor account, then **Continue setup** |
| Wrong sponsor key in the build (untested) | A key that can't be read (for example not valid base58) is ignored: the Log notes `sponsor key in this build is unreadable; using the faucet only`, setup runs as without a sponsor (a faucet refusal ends in `The testnet faucet refused (…)`), and Start over deletes nothing. A valid key that isn't on the sponsor account fails, once the faucet refuses, at the nonce lookup with `<sponsor> has no access key ed25519:<…>: <RPC text>` or `query: UNKNOWN_ACCESS_KEY …` (depending on the RPC); `The network refused the transaction: This key can't sign for the account any more` is also possible | Fix `tapvault.sponsor.key`, rebuild, and install the new APK over the old one (same build machine, so the unfinished setup is kept) |
| Start over | `Started over` / `deleted <account>; …. Their NEAR went back to <sponsor>.` (a failure is listed as `couldn't delete <account> (<reason>)`) | Normal: with a sponsor, the fee account and a wallet account with nothing deployed are deleted and their NEAR goes to the sponsor. After the contract is deployed the wallet account is left as it is |

**Wallet and fee account balances.**

| Problem | Message | What to do |
|---|---|---|
| Wallet can't cover a payment | `The wallet only has <x> NEAR available to send` | Home → Receive shows the wallet account; send testnet NEAR to it from any testnet account |
| Fee account empty (gas) | `The network refused the transaction: Not enough NEAR in the account` on any action after setup | Send testnet NEAR to the fee account `tv-…-gas…` (Settings → `Fee account` opens it on the explorer; the Log line `wallet …, fee account …` names it). A sponsor-funded fee account starts with 0.5 NEAR |

Ways to get testnet NEAR to send: create an account with the faucet (for
example `near account create-account sponsor-by-faucet-service <name>.testnet autogenerate-new-keypair save-to-keychain network-config testnet create`)
and send from it, or send from the sponsor with its key.

Check any account's balance:

```bash
curl -s https://rpc.testnet.fastnear.com -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"query","params":{"request_type":"view_account","finality":"final","account_id":"tapvault-skyyy.testnet"}}' \
  | jq -r .result.amount   # yoctoNEAR (10^24 per NEAR)
```
