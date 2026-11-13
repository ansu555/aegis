# Tapvault overview

What Tapvault is, who does what, how someone uses it from install to payment,
what the wallet's rules are, the words used across these docs, and the
hackathon it was built for.

> **Status, 12 Nov 2026.** NEAR testnet only. All automated tests pass,
> including live testnet runs with simulated cards of all three kinds (Visa
> fDDA, Mastercard CDA, classic DDA). Nothing has been run against a real bank
> card or a real phone's NFC yet. Details: [STATUS.md](STATUS.md).

## What Tapvault is

Tapvault is a self-custodial NEAR wallet whose funds move only when two
things you already own agree:

- **Your contactless bank card.** Its EMV chip holds an RSA key that the bank
  put there and that can't be copied out. Payment terminals ask the chip to
  sign a random number to prove the card is genuine (offline data
  authentication). Tapvault asks for the same kind of signature, over a
  challenge the wallet contract picked. No PIN is entered and no payment is
  made: nothing is sent to the bank.
- **Your phone.** The app creates a P-256 key inside the Android Keystore's
  secure hardware (the TEE). The key can't be exported. It signs only after a
  fresh fingerprint or screen-lock check (fingerprint only on Android 9 and
  10), every single time, under a system prompt that says what you are
  approving.

A NEAR smart contract, deployed to the wallet's own account, checks both
signatures on-chain and enforces spending limits, delays and freeze. At the
end of setup the account's own access key is deleted, so from then on only
the contract's rules can move the funds. There is no seed phrase and no extra
device to buy.

Why a skimmer can't use your card: a card has no screen and signs whatever a
reader sends it. So the contract first commits the payment, then derives the
card's challenge from block randomness captured in a *later* block. A
signature collected from your pocket earlier can't match it, and a card
signature alone never moves funds anyway. What this does not protect against
(malware on the phone plus a reader against the card at the same moment) is
explained in [DESIGN.md](DESIGN.md).

What a tap does to the card: each signing attempt is a normal contactless
transaction for the chip. Its transaction counter (ATC) goes up, and with
Visa online fDDA and with CDA (what Visa and Mastercard contactless cards are
expected to need) the card also produces a cryptogram, usually an
online-authorisation request (ARQC). Tapvault never sends anything to the
bank or the card network.

## Who does what

| Part | What it is | What it does | What it can't do |
|---|---|---|---|
| Phone | The Tapvault Android app and its P-256 phone key | Builds every action, shows it in the fingerprint prompt, signs it, reads the card over NFC, sends the transactions | Move funds without the card |
| Bank card | The EMV chip's own RSA key (the "ICC key") | Signs the contract's challenge when tapped | Approve a transfer on its own: cards sign on contact, without a screen, so every transfer also needs a phone approval |
| Wallet contract | `contract/`, deployed on the wallet account | Verifies the P-256 and RSA signatures, applies the policy, holds the NEAR | Be upgraded: each wallet keeps the code it was created with |
| Fee account | A second NEAR account whose key the app stores | Pays the gas for every call after setup | Authorise anything |
| Sponsor (optional) | A funded testnet account built into the APK | Creates and funds the two accounts when the testnet faucet refuses; gets their NEAR back if a setup is abandoned | Anything after setup |

What the app sets up, every time:

- **Signers:** the phone (signer index 0) and one card (signer index 1).
  Threshold 2, so every payment and every setting change needs both
  (freezing and cancelling need only one).
- **Limit:** one, for NEAR, with the "Instant payment limit" and "Daily limit" amounts entered
  on "Set your spending limits" (defaults 1 and 2 NEAR).
- **Delays:** the shortest the contract allows (`Delays.SHORTEST`): transfers
  1 h, setting changes 1 h, recovery 24 h.
- **Accounts:** a wallet account `tv-<10 hex>.testnet` and a fee account
  `tv-<10 hex>-gas.testnet` from the faucet; when the faucet refuses and the
  APK has a sponsor, sub-accounts of the sponsor instead
  (`tv-<10 hex>.<sponsor>`, `tv-<10 hex>-gas.<sponsor>`). Each account is
  decided separately, so a wallet can have one of each.

How the pieces connect, with sequence diagrams for every flow:
[ARCHITECTURE.md](ARCHITECTURE.md).

## The user journey

Screens and buttons are named as they appear in the app. Errors and what to
do about them: [TROUBLESHOOTING.md](TROUBLESHOOTING.md). The step-by-step
test plan for a real phone: [TESTING.md](TESTING.md).

### 1. Install

- The APK is a debug-signed build from `android/build-apk.sh`, sideloaded with
  `adb install` or by opening the file. Play Protect, MIUI or ColorOS may warn
  or scan it; allow installing unknown apps. Details:
  [ANDROID.md](ANDROID.md).
- The phone needs Android 9 or newer, NFC hardware, NFC switched on, and a
  screen lock (PIN, pattern or password). Android 9 and 10 also need an
  enrolled fingerprint. On a phone without NFC the app installs but says
  "This phone has no NFC, so it can't read a bank card. Use an Android phone
  with NFC." and won't start setup.
- Uninstalling the app (or clearing its storage) deletes the phone key. A
  wallet whose phone key is gone can never approve anything again (see
  Recovery in the policy table below).
- An update must be signed with the same key as the installed app. Debug
  builds from different computers have different keys, and Android then
  refuses the update until you uninstall, which deletes the phone key.

### 2. Welcome

- Three swipeable slides: "Your bank card is your hardware wallet", "Two
  keys, every payment" and "Limits that have your back". A bank card is the
  only kind of signer; there is no demo or simulated card in the app.
- **Check my card first** (optional) opens Card check: tap **Check a card** and
  hold the card to the phone. The app reads the chip, recovers the card's key
  through its certificates, tries the signing methods that fit the card one
  after another until one works, and says **Can sign** or **Can't sign**.
  Nothing touches the chain. **Share report** sends a text
  report: no card number, track data, name or certificates; only the last
  four digits. See [CARDS.md](CARDS.md).
- **Create a new wallet** opens "Set your spending limits": "Instant payment
  limit" (default 1 NEAR) and "Daily limit" (default 2 NEAR), each with
  preset chips. Payments within both go through instantly; anything bigger
  waits an hour. A comma works as the decimal point. The screen shows the
  problem under the field ("The daily limit must be at least the instant
  limit.") and keeps **Continue** disabled until both are amounts above zero.
- **Continue** starts setup. It is disabled on a phone without NFC (so is
  **Check a card**). The screens show "This phone has no NFC" or "NFC is off"
  with an **Open NFC settings** button. While NFC is off, Continue is
  refused with "Can't start yet": "Turn on NFC in Android settings (Connected
  devices → NFC) first." Limit errors caught by the app itself ("Enter both
  limits as amounts like 1 or 0.5.", "The per-day limit must be at least the
  at-once limit.") use the same "Can't start yet" banner.

### 3. Setup (about a minute: one card tap, three fingerprints)

The Setup screen ("Setting up your wallet", or "Ready to set up" / "Paused"
/ "All set") shows a progress ring ("n/6 steps") and the six steps, each
ticked when done; once the accounts exist it also shows the wallet account
("Account: tv-<10 hex>.testnet"). The steps run in this order:

1. **Phone key in secure hardware:** the P-256 key is created in the Keystore
   (no prompt). Without a screen lock: "Set up a screen lock (PIN, pattern or
   password) on this phone first, then continue. (…)", with the system's
   error in the brackets. On Android 9 or 10 without
   a fingerprint: "On this Android version Tapvault needs a fingerprint. Add
   one in Settings → Security, then try again."
2. **Wallet account on NEAR:** the wallet and fee accounts are created, from
   the faucet or, if it refuses and a sponsor is built in, from the sponsor.
3. **Card read and signed:** tap sheet "Hold your bank card to the back of
   the phone to add it". In one tap the app reads the card's certificate
   chain and the card signs its enrolment challenge. A card that can't sign,
   or whose key the contract wouldn't accept (checked before the card is
   asked to sign), stops setup here, before anything is deployed: "This card
   can't sign for Tapvault: … Tap Continue setup to try another contactless
   card, or Start over." The card's report (as in Card check) then shows on
   the Setup screen, with a **Share card report** button.
4. **Wallet contract deployed:** fingerprint prompt "Create your Tapvault
   wallet". One transaction deploys the contract and initialises it with the
   phone key.
5. **Card added as a signer:** fingerprint prompt "Add Visa card ****1234 as
   a signer" (your card's network and last four digits).
6. **Limits set, account locked:** fingerprint prompt "Lock the wallet: up to
   1 NEAR at once, 2 NEAR a day". The contract sets threshold, delays and
   limits and deletes the wallet account's own key.

The result is "Your wallet is ready" ("Funds now move only with your phone
and your card.") with a "View on explorer" link to the wallet account.

If anything is interrupted, **Continue setup** resumes: each step checks the
chain first and skips what already landed, and a card that was read is kept
(no second tap) until setup finishes. NFC has to be on only until the card
has been read. **Start over** asks for confirmation ("Start over?"), then
removes the phone key and the saved setup. If the APK has a sponsor, the fee
account is deleted, and so is the wallet account if the contract step
hasn't been reached; their NEAR goes to the sponsor, and a "Started over"
result lists what was deleted. A half-built wallet (contract already
deployed) stays on testnet. Without a sponsor nothing is deleted on-chain,
and the dialog says so: "The phone key and this setup's saved state are
removed. The accounts made so far stay on testnet with their test NEAR."

### 4. Fund the wallet

- **Home → Receive** (or the QR button at the top right) opens a sheet with a
  QR code of the wallet account id, **Copy** and **Share**. Send testnet NEAR to it from any testnet
  account.
- A faucet-created wallet starts with 10 NEAR; a sponsor-created one with
  5.5 NEAR, minus the gas of the three setup transactions. About 3.35 NEAR of
  either stays locked to pay for storage (3.34 NEAR for the contract code,
  the rest for the account and the wallet's state). Home shows the
  spendable balance under "Total balance", a pill "X NEAR available
  instantly", and at the bottom "+ X NEAR reserved for the wallet contract's
  storage".

### 5. Pay within the limits

1. **Send** → amount and a NEAR account (`friend.testnet`, or a 64-hex
   implicit account). **Half** fills half the spendable balance; **Max
   instant** fills the most that can go out instantly (the instant allowance left,
   capped at the spendable balance minus 0.01 NEAR). The summary under the
   amount says "Goes out instantly" or "Waits 1 h · you can cancel it".
2. **Review** opens a summary sheet; dragging **Slide to approve** to the end starts. Before any prompt the app checks that NFC is
   on, the wallet isn't frozen, the spendable balance covers the amount, and
   the receiver exists.
3. Fingerprint prompt "Send 0.5 NEAR to friend.testnet". The phone signs the
   payment and the app proposes it.
4. About a second later the contract has sealed the card's challenge. Tap
   sheet: "Tap your card to approve sending 0.5 NEAR". Hold the card still
   until the sheet closes; while it reads, the sheet says "Keep holding the
   card…".
5. One transaction carries the card approval and the execution. Result:
   "Sent 0.5 NEAR" with an explorer link. If the contract doesn't count the
   payment as instant after all (it decides with its own clock and
   allowance), the card approval is sent on its own and the result is
   "Approved, waiting": "The wallet didn't treat this as within your limits
   (the phone's clock may be off). Execute it from Activity when it's
   ready."

If the tap fails after the fingerprint, the payment is saved and waits
10 minutes for the card: send the same amount to the same account again
while at least 30 s of that window are left (no second fingerprint; the app
finishes the same payment), or use **Activity → Approve · card**, or cancel
it.

### 6. Pay over the limits

Same steps, but the result is "Approved, waiting 1 h". Nothing runs by
itself: after the hour, open **Activity** and tap **Execute** (it appears
once the delay is over). Until then you can **Cancel** it (fingerprint) or
freeze the wallet. Execute goes away 7 days after the delay ends, when the
status turns to "Expired: it can no longer execute; cancel it".

### 7. Freeze and unfreeze

- **Freeze with card:** the round snowflake button on Home, then "Freeze" in
  the "Freeze the wallet?" dialog, then one tap ("Tap your card to FREEZE the
  wallet").
- **Freeze with phone:** Settings → "Freeze with phone", fingerprint prompt
  "FREEZE the wallet (blocks all transfers)".
- A frozen wallet refuses every transfer, including ones already approved.
  Home shows "Wallet frozen".
- **Request unfreeze** (on Home while frozen): fingerprint prompt "Unfreeze
  the wallet (takes effect after 1 h)", then a card tap ("Tap your card to
  approve unfreezing"). Result: "Unfreeze approved" ("It can execute in 1 h
  (Activity tab)."). After 1 h, **Activity → Execute**. NFC is checked
  before the prompt, and an earlier request still waiting for the card (with
  more than 30 s of its 10-minute card window left) is reused: no new
  proposal and no fingerprint, just the tap.
- Executing the unfreeze (like any setting change) voids every other open
  proposal. They show as "Void: signers changed" and can be removed with
  **Clean up**.

### 8. Other screens

After setup a floating glass tab bar switches between Wallet, Activity and
Settings (Activity shows a count while proposals are open).

- **Home:** the wallet switcher and a QR button at the top, "Total balance",
  the card (tap it for Your card; frosted while frozen), "Total balance", the
  instant pill ("Frozen" while frozen), the round buttons Send, Receive and
  Freeze (with the card), "Pending" (only while something waits) and "Spent
  today" with a meter. Pull down to refresh.
- **Activity:** every open proposal with its status ("Waiting for
  approvals", "Approved · can execute in …", "Approved · ready: tap Execute",
  "Approved · wallet is frozen", "Expired: it can no longer execute; cancel
  it", "Void: signers changed") and the buttons that apply: Approve · card,
  Approve · phone, Execute, Cancel, Clean up. Approving a NEAR payment by
  card here also executes it when the phone has approved it, the wallet
  isn't frozen and it is within the limits ("Approved and sent"); if the
  contract doesn't agree that it can run yet, the card approval is kept
  ("Approved"). Each proposal shows a Phone → Card → delay tracker with a
  countdown. Below the list: "All transactions" (the explorer).
- **Your card** (tap the card on Home, or Settings): the enrolled card and the
  same check as on Welcome, for any card.
- **Settings:** the wallet name (**Rename**), "My wallets", wallet and fee
  account (explorer links), where the phone key lives (for example "Trusted
  Execution Environment"), how the card signs, approvals, the delays,
  the limits, **Freeze with phone** (while not frozen), the storage reserve,
  the in-app log ("Technical log", the last 60 lines), and **Forget this
  wallet on this phone**. Forgetting deletes the phone key; with a single card the wallet
  can never approve anything again, so its NEAR stays locked for good
  (testnet NEAR only).

## What the policy enforces

The contract enforces all of this on-chain; the app can't bypass it.
Constants are in `crates/tapvault-core/src/policy.rs`,
`contract/src/lib.rs` and `contract/src/types.rs`. Method-level detail: [CONTRACT.md](CONTRACT.md).
Reasoning: [DESIGN.md](DESIGN.md).

| Rule | What the contract enforces | In wallets the app creates |
|---|---|---|
| Signers | 2 to 8 signers (`MAX_SIGNERS` = 8), at least one P-256, no duplicates; threshold at least 2 (`MIN_THRESHOLD`) and at most the number of signers | Phone + one card, threshold 2 |
| Transfers | `threshold` approvals, including at least one P-256 approval (phone or passkey). Cards alone never move funds | Phone and card for every payment |
| Instant limits | Per asset: `instant_max` per transfer and a `daily_max` allowance that refills continuously (`daily_max` per 24 h, so there is no window edge to exploit). An asset with no limit is always delayed | NEAR only, from "Instant payment limit" / "Daily limit" |
| Over-limit transfers | Wait `delays.transfer`, counted from the moment the proposal is fully approved (`ready_ts`), not from when it was proposed. Nothing executes automatically | 1 h, then **Execute** in Activity |
| Execution window | 7 days after the delay ends (`EXECUTION_WINDOW`), then "Proposal expired" | Same; Activity then hides Execute and shows "Expired: it can no longer execute; cancel it" |
| Card approval window | A card must approve within 10 minutes of the challenge being sealed (`CARD_APPROVAL_WINDOW` = 600 s), else "Card approval window has closed; propose again" | Same; Send reuses an unfinished payment inside the window |
| Proposal caps | Per signer: 24 proposals per 24-hour window (`MAX_PROPOSALS_PER_DAY`; the counters also reset when a setting change executes), and 4 open proposals that can still complete (`MAX_OPEN_PER_SIGNER`: same signer set, and either fully approved or at most 11 minutes old), else "Proposal rate limit reached for today" or "This signer has too many open proposals; cancel or wait for some" | The phone proposes everything, so these are the phone's caps |
| Freeze | Any single signer, phone or card. Blocks every transfer, including approved ones; new transfers can't be proposed. Each freeze signature works once (`freeze_nonce` becomes a new random value on every freeze and unfreeze) | Freeze with card (Home), Freeze with phone (Settings) |
| Unfreeze | A setting change (`ConfigOp::Unfreeze`), delayed like any other | Request unfreeze, then Execute after 1 h |
| Setting changes | Always delayed: the `config` delay with a P-256 approval, the longer `recovery` delay without one. Executing one voids every other open proposal (`signer_epoch` moves on) | The app only proposes Unfreeze |
| Cancel | A P-256 signer can cancel any proposal; a card can cancel any proposal except one that removes that card | Activity → Cancel, with the phone |
| Clean-up | Anyone can delete a proposal that is void, was never fully approved within 1 day (`UNAPPROVED_TTL`), or is past every possible execution window (`close_stale`) | Activity → Clean up (offered whenever `close_stale` would accept the proposal) |
| Recovery | If the phone is lost, the remaining signers propose replacing it; the change waits the `recovery` delay | Not possible: with one card, the remaining signers can't reach threshold 2 |
| Delay bounds | Transfer and config 1 h to 30 days; recovery 1 to 30 days and at least `config`. Contract defaults 24 h / 24 h / 72 h | 1 h / 1 h / 24 h |
| Setup lock | `new` only from the wallet account itself; `setup_finalize` must be signed by it and deletes that key | The app deploys and initialises in one transaction |

## Not in the app yet

The contract supports more than the app uses:

- **Tokens.** The contract can send NEP-141 tokens (`Asset::Ft`, e.g. USDC)
  with their own limits; the app only sends NEAR.
- **Changing settings after setup.** Limits, delays, threshold and signers
  can change through delayed config proposals; the app only proposes Unfreeze.
  You can't add a second card or a guardian, so app wallets can't be
  recovered.
- **Passkeys and FIDO2 keys** as P-256 signers (`webauthn` in the contract);
  there is no client for them.
- **Paying at a shop terminal** (the merchant POS flow in DESIGN.md): not
  started.
- **Automatic execution** of delayed payments and unfreezes.
- **iPhone:** iOS doesn't let apps talk to payment cards over NFC.
- **Mainnet:** the app is built for testnet only.

## Glossary

### Card and EMV terms

| Term | Meaning |
|---|---|
| EMV | The chip-card standard behind bank cards worldwide (EMV Books 1 to 4, plus a contactless kernel per network) |
| Contactless, NFC | The card and the phone talk over NFC (ISO 14443). The app keeps Android "reader mode" on while it is on screen |
| ISO-DEP | The ISO 14443-4 transport that bank cards speak (`IsoDep` on Android). Transit cards, stickers and key fobs that don't speak it can't sign |
| APDU | One command sent to the card and its response. Every response ends in a 2-byte status word: `9000` is OK; `6985` "conditions of use not satisfied", `6A82` "not found", `6D00` "command not supported" |
| AID, RID | Application Identifier: selects a payment application on the card. Its first 5 bytes are the RID, the network: `A000000003` Visa, `A000000004` Mastercard, `A000000524` RuPay |
| PPSE | The directory of a contactless card's applications, selected by the name `2PAY.SYS.DDF01` |
| TLV, tag | Card data is tag-length-value encoded (BER-TLV). Tags are written in hex, e.g. `9F37` |
| PDOL, DDOL, CDOL1 | Data Object Lists: the terminal values a card wants with GET PROCESSING OPTIONS, INTERNAL AUTHENTICATE and GENERATE AC respectively. The default DDOL is `9F37 04` (just a 4-byte UN) |
| GPO | GET PROCESSING OPTIONS (`80 A8`): starts a transaction; returns the AIP and AFL (and, on Visa fDDA cards, the signature) |
| AIP | Application Interchange Profile: flags saying whether the card supports SDA, DDA and CDA |
| AFL | Application File Locator: which records to read with READ RECORD |
| UN (`9F37`) | Unpredictable Number: the terminal's random number. Tapvault puts the first bytes of its 32-byte challenge here (4 on almost every card; the contract accepts 4 to 8) |
| ODA | Offline data authentication: how a terminal checks the card is genuine without going online (SDA, DDA, fDDA, CDA) |
| SDA | Static data authentication: no per-transaction signature, so useless as a signer |
| DDA | Dynamic data authentication: the card RSA-signs data that includes the UN, in answer to INTERNAL AUTHENTICATE |
| fDDA | Visa's fast DDA (payWave): the signature comes with the GPO answer (or in the last AFL record) and covers the UN, amount, currency and the Card Authentication Related Data (`9F69`) |
| CDA | Combined DDA / application cryptogram: the card signs along with its GENERATE AC answer (typical of Mastercard contactless) |
| INTERNAL AUTHENTICATE | The DDA command (`00 88`). Many contactless cards don't answer it |
| GENERATE AC | Asks the card for an application cryptogram (`80 AE`). P1 `0x80` asks for an ARQC, `0x40` for a TC; `+0x10` requests a CDA signature |
| ARQC, TC, AAC | Cryptogram types: online authorisation request, approved offline, declined. Tapvault never sends any of them to a bank |
| TTQ (`9F66`) | Terminal Transaction Qualifiers. The app first asks for an online cryptogram (`37804000`, so no offline counters are used) and falls back to allowing offline (`37004000`) |
| ATC (`9F36`) | Application Transaction Counter: goes up on every card transaction, including every Tapvault signing attempt |
| SDAD (`9F4B`) | Signed Dynamic Application Data: the card's RSA signature. Recovered with the card's public key it reads `6A \| format \| 01 \| Ldd \| ICC dynamic data \| BB… \| SHA-1 \| BC` |
| Signed Data Format | The second byte of a recovered SDAD: `05` (DDA, CDA, offline fDDA) or `95` (Visa fDDA on an online transaction). Contract builds before 12 Nov accepted only `05` |
| Terminal dynamic data | The bytes from the reader that the SDAD's SHA-1 covers: the DDOL values (DDA), UN + amount + currency + `9F69` (fDDA, or a shorter form), or the UN (CDA). Its layout per card (`un_offset`, `un_len`, `terminal_data_len`) is stored on-chain |
| `9F69` | Card Authentication Related Data (Visa fDDA): fresh on every transaction. When the GPO answer doesn't carry it (or the signature), the app reads the last AFL record again after that GPO instead of using a cached copy |
| CA public key (CAPK) | A network's root key, found by RID + index. The app's list is `android/core/src/main/resources/capk.txt` |
| Issuer certificate (`90`), card certificate (`9F46`) | The chain CA → issuer → card key. Verified on the phone only, because the certificates contain the card number |
| ICC public key | The card's own RSA public key (modulus 512 to 1984 bits, exponent 3 or 65537). This is what goes on-chain as the card signer |
| PAN | The card number (tag `5A`; also inside track data and the certificates). It never leaves the phone; screens and reports show only the last four digits |
| Track 2 equivalent (`57`) | Magstripe-style data including the PAN and expiry. Never logged or shared |
| Card recipe | Tapvault's note of how this card signed at enrolment (application, method, amount, variant, TTQ), kept on the phone so later taps sign the same way (`CardRecipe`) |
| Card check, card report | The read-only probe on the Card check screen and the text it can share (`CardService.inspect`, `CardReport`) |

### Android terms

| Term | Meaning |
|---|---|
| Android Keystore | The system service that creates and uses keys without letting the app read them |
| TEE, StrongBox | Where Keystore keys live: the Trusted Execution Environment (an isolated part of the main processor) or a separate secure chip. Tapvault uses the TEE; StrongBox keys failed at signing time on some phones |
| BiometricPrompt | The system fingerprint / face / screen-lock dialog. Tapvault's shows the title "Tapvault", what you are approving, and "Your phone key signs only what you approve here." |
| Reader mode | An NFC mode in which the foreground app gets every tag directly, so Android doesn't show "No supported app for this NFC tag" |
| Sideloading, debug-signed APK | Installing an APK outside the Play Store. Tapvault's APK is signed with the build machine's debug key |
| adb | Android Debug Bridge, the command-line tool used to install the APK and read system logs |

### NEAR terms

| Term | Meaning |
|---|---|
| Account | Named (`alice.testnet`), sub-account (`tv-….tapvault-skyyy.testnet`, created by its parent), or implicit (a 64-hex id that exists once it receives NEAR) |
| Access key | An ed25519 key that signs transactions for an account (full-access or function-call). A Tapvault wallet has none after setup |
| Nonce | A per-access-key counter; every transaction must use a higher one |
| Transaction, action | A signed list of actions for one receiver. The app uses CreateAccount, Transfer, AddKey (full access), DeployContract, FunctionCall and DeleteAccount |
| Receipt | The unit of execution. All actions of one transaction run in one receipt and fail together; a failed receipt undoes its own state changes. Transfers and cross-contract calls are new receipts in later blocks |
| Promise, callback | An asynchronous call. `execute` sends the transfer as a promise; `on_transfer` runs after it and gives the allowance back if it failed |
| Gas, Tgas | Execution cost; 1 Tgas = 10^12 gas; at most 300 Tgas per transaction. Paid in NEAR by the transaction's signer (the fee account, after setup) |
| yoctoNEAR | 10^-24 NEAR. Amounts are integers in yoctoNEAR (`U128` in the contract, `BigInteger` in Kotlin) |
| Storage staking | An account must keep 10^19 yoctoNEAR (0.00001 NEAR) per byte it stores: about 3.34 NEAR for the 334,258-byte contract |
| `random_seed` | Per-block randomness from the block producer's VRF. Tapvault's card challenge is derived from it |
| Finality | `optimistic` (latest) or `final`. The app reads views optimistically, builds transactions on a final block hash, and waits until `EXECUTED_OPTIMISTIC` |
| RPC | The JSON-RPC endpoints the app talks to (methods `query`, `block`, `send_tx`, `tx`) |
| Faucet | `helper.nearprotocol.com/account`: creates a `.testnet` account with 10 NEAR; rate-limited per IP (about 10 accounts per 12 h, then HTTP 429) |
| `code_hash` | Base58 SHA-256 of an account's contract code, shown by `view_account` |
| Borsh | The binary encoding NEAR uses for transactions and state. Tapvault also uses it for the bytes signers sign |
| NEP-141 | The fungible-token standard (`ft_transfer` with a 1 yoctoNEAR deposit) |
| NEP-297 | The event-log format: Tapvault logs `EVENT_JSON:{"standard":"tapvault","version":"1.0.0",…}` |
| near-sdk, cargo-near, near-workspaces, sandbox | The Rust contract SDK (~5.14), its build tool (0.17.0), the test harness (0.20) and the local node it runs (nearcore 2.6.5) |
| Testnet, mainnet | NEAR's test and production networks. Tapvault is testnet only |
| Explorer | testnet.nearblocks.io; the app links every transaction there |

### Tapvault terms

| Term | Meaning |
|---|---|
| Signer | An enrolled key at an index: `P256` (phone or passkey) or `EmvCard` (a card's ICC key and its terminal-data layout) |
| Deliberate signer | A P-256 signer: it needs a user action behind a screen. Every transfer needs at least one |
| Key hash | A signer's identity: SHA-256 of a domain tag and the public key (`key_hash_p256`, `key_hash_emv`). Computable from the card's public key; it doesn't reveal the PAN |
| Proposal | An action (a transfer or a list of config ops) waiting for approvals, numbered from `next_proposal_id` |
| Intent hash | `H("tapvault/near/v1/intent" ‖ len ‖ account ‖ proposal id ‖ borsh(action))`: what the phone signs to propose or approve |
| Seal, seed | `seal_challenge` runs in a later block than `propose` and stores that block's `random_seed` on the proposal, once |
| Card challenge | `H("tapvault/near/v1/card-approve" ‖ intent ‖ seed)`; the card signs its first `un_len` bytes as the UN |
| Approval bitmap | Bit i set means signer i approved (`approvals`) |
| `ready_ts` | When the proposal first had every approval it needs; delays count from here |
| Signer epoch | A counter bumped by every executed config change; proposals from an older epoch are void ("stale") |
| Freeze nonce | Part of every freeze message. It starts at 0 and becomes a new random value on every freeze and unfreeze, so a freeze signature works once and can't be collected for a future nonce (one for the current nonce can be, see [DESIGN.md](DESIGN.md) known gaps) |
| Enrolment | Adding a card as a signer during setup: the phone authorises it and the card signs its enrolment challenge (proof of possession) |
| Fee account | The account that signs and pays for every call after setup (called "the relayer" in DESIGN.md). It has no authority over funds |
| Sponsor | An optional funded testnet account, built into the APK, that creates accounts when the faucet refuses |
| Simulated card | Test fixtures with private keys that are public on purpose: `SimCard` (Kotlin tests) answers real APDUs, with a test CA → issuer → card chain, like a DDA, a Visa-style fDDA or a Mastercard-style CDA card; `TestCard` (Rust tests) produces card signatures (SDADs) directly. Not in the app |

## Hackathon context

- **Event:** NEAR HACKATHON (nearhackathon.com). Submission deadline
  **14 Nov 2026, 23:59 UTC**; results by 18 Nov.
- **Submission needs:** a public repository; a public demo post on X tagging
  `@NEARProtocol` with `#NEARHackathon`; a NEAR mainnet account for the payout.
- **Tracks entered:** Real-World Money (main: pay with the card you already
  carry), AI Security & Defense (spending limits and a policy engine that
  neither a person nor an agent can bypass), Open Innovation (fallback).
- **Repository:** github.com/skyyycodes/tapvault, private until submission.
  Everything runs on NEAR testnet.
- **History:** the protocol design started as a Solana prototype on
  9 Nov 2026 (not in this repo); everything here is the NEAR implementation,
  built during the hackathon window.
- **Before submitting:** real-phone and real-card testing, then the demo video
  ([DEMO.md](DEMO.md)). What is left is tracked in [STATUS.md](STATUS.md).
