# Hardware-wallet security plan: interface, user flows, implementation

Status: **plan, 13 Nov 2026. Phase 1 is built** (app only, same contract):
several cards per wallet, Add backup card (5.3), Security Center with
read-only rows (4.3, without the protection score and the CHECKS rows, which
need phase 2's integrity check and watchtower), the Home pill (4.2, without
the integrity state), hold-to-sign (4.5) and `FLAG_SECURE`. Test results:
[STATUS.md](STATUS.md). **Phase 2 is built too** (app only): lost card
(5.4), integrity check (5.7), review warnings (4.4), the gap 6b fix,
watchtower alerts (5.9, framework JobScheduler instead of WorkManager, to
add no dependency) and the app lock; Security Center has the protection score
and the CHECKS rows. **Phase 3 is built** (app + client): backup card
during setup, recover on a new phone (5.5), fingerprint only (5.8, setup
option and key switch) and a guardian phone. **Phase 4 is built in the
contract source** (v0.2): possession proofs, the seed in the proposal view,
a receiver allowlist and a delayed `UpgradeCode`; the app uses each only on
v0.2 wallets (Security Center → Instant receivers for the allowlist; adding
and removing a receiver both wait the settings delay, unlike the "instant to
remove" in section 6). What is tested and what is still for a phone:
[MANUAL-TESTS.md](MANUAL-TESTS.md). It describes how Tapvault gets the features and guarantees
of a hardware wallet (Ledger, Trezor, Tangem), what each screen and flow looks
like, and in what order to build it.

Read [DESIGN.md](DESIGN.md) first for the security model this builds on.

---

## 1. The idea in one paragraph

A hardware wallet keeps its key in a secure chip and makes you confirm every
signature on the device. Tapvault has **two** secure chips, the bank card's
EMV chip and the phone's Keystore (TEE). Neither can be programmed by us, so
every rule a hardware wallet runs in its firmware, Tapvault runs either **in
the contract** (enforced by the chain, which no thief can skip) or **in the
app** (convenience and consent, which an attacker with their own software can
skip). This plan labels every feature with where it is enforced so we never
oversell an app-side check as a hardware guarantee.

| Label | Meaning |
|---|---|
| **CHAIN** | Enforced by the wallet contract. Holds even if the phone is hacked or someone uses their own client. |
| **TEE** | Enforced by the phone's secure hardware (Android Keystore): key can't be exported, wrong attempts are throttled by hardware. |
| **CARD** | Enforced by the bank card's chip: the key can't be exported or copied. |
| **APP** | Enforced by the Tapvault app only. Real protection against mistakes and casual misuse; not against an attacker running their own software. |

---

## 2. Hardware wallet features and what Tapvault does for each

| # | Hardware wallet feature | Ledger / Trezor / Tangem | Tapvault today | Tapvault plan | Where |
|---|---|---|---|---|---|
| 1 | Key in a secure chip, never exported | Secure element | Card key (CARD) + phone key (TEE), two chips | Same. Show which chip holds each key in Security Center | CARD + TEE |
| 2 | PIN / access code with attempt limit | Device PIN, wipe or delay after wrong tries | Fingerprint or screen lock on every phone signature, hardware-throttled | Add **Strict mode**: fingerprint only, no PIN fallback (stops a thief who watched you type your phone PIN) | TEE |
| 3 | Confirm on the device ("what you see is what you sign") | Trusted screen + buttons | The phone computes the hash it signs and the fingerprint prompt names amount and receiver | **Review screen** + slide to approve + prompt text from the same values; fix the two places that sign RPC data (DESIGN gap 6) | APP (+ TEE prompt) |
| 4 | Physical presence for every signature | Button press / card tap | Card tap needed for every payment | **Hold-to-sign timer** (Tangem-style security delay) | APP |
| 5 | Backup (seed phrase / backup cards) | 24 words or 2–3 cloned cards | **None**: phone + 1 card, losing either one strands the wallet | **Backup cards** (up to 7 cards + phone, 8 signers) and **guardian phone** | CHAIN |
| 6 | Recovery when the device is lost | Restore seed on new device | Not possible | **Recover on a new phone** with two cards, after the 24 h recovery delay | CHAIN |
| 7 | Genuine check (device is real) | Attestation | Card's certificate chain checked against the card network's CA key; contract verifies every card signature | Add **phone key attestation** check and show the result | CHAIN + TEE |
| 8 | Firmware integrity | Signed firmware | Wallet code is fixed (no keys, no upgrade) | **Wallet integrity check**: code hash = shipped build, access keys = none, signers = what this phone expects | CHAIN (checked by APP) |
| 9 | Spending limits | Only enterprise products | Instant limit + daily limit | Show remaining allowance on Home and Send | CHAIN |
| 10 | Time-lock on big transfers | None | Over-limit transfers wait the transfer delay (1 h in the app) | Show countdown, notify, allow cancel | CHAIN |
| 11 | Panic button | None | **Freeze** with phone or card | One-tap Freeze on Home, in notifications and on the lost-card flow | CHAIN |
| 12 | Cancel a pending transaction | Not possible after signing | Cancel open proposals | Same, from notification | CHAIN |
| 13 | Alerts on activity | None (device is offline) | None | **Watchtower**: background check, notify on any proposal this phone did not make | APP |
| 14 | Address verification | Show address on device | Receive sheet with account id + QR | Add **address book**, first-time-recipient warning, look-alike warning, clipboard check | APP |
| 15 | Screen privacy / anti-screenshot | Separate device | None | `FLAG_SECURE` on all wallet screens, app lock when backgrounded | APP |
| 16 | Hidden / decoy wallet (passphrase) | Trezor passphrase, Ledger PIN-2 | Several wallets per phone (wallet picker) | Later: optional "hide this wallet from the picker" | APP |
| 17 | Allowlist of receivers | Ledger Enterprise | None | Contract v0.2: allowlisted receivers skip the delay, new ones wait | CHAIN (new wallets only) |

What Tapvault already does better than a single hardware wallet: a stolen
card alone can't move funds (CHAIN, `NeedsUserVerifiedApproval`), big
transfers wait and can be cancelled, and the wallet can be frozen. A
Tangem card + its access code empties the wallet at once.

---

## 3. Threats and the answer to each

| Threat | Today | After this plan |
|---|---|---|
| **Card stolen** | Thief can't pay (needs phone). Can start proposals and freeze once (DESIGN gap 9) | Same + watchtower alerts you, lost-card flow removes it |
| **Card skimmed in a crowd** (reader held near your pocket) | Same as stolen: signatures alone can't move funds | Same + alert |
| **Phone stolen, locked** | Thief can't sign (fingerprint / screen lock, hardware-throttled) | Same. With backup cards you recover on a new phone |
| **Phone stolen after thief watched you type the phone PIN** | Thief can sign with the PIN. Still needs the card | Strict mode: PIN no longer signs; fingerprint only |
| **Phone + card stolen together (bag), PIN known** | **Thief can pay up to the instant limit at once**, more after the 1 h delay unless you cancel or freeze | Strict mode stops it (fingerprint needed). Without strict mode: low instant limit + freeze from another device (guardian phone or a backup card on any Tapvault phone) |
| **Malware on the phone shows a fake amount** | Phone signs the hash it computed; prompt names amount and receiver | Same + gap 6 fixed + over-limit delay as backstop |
| **Dishonest RPC node** | Gap 6: can steer a card tap to sign another challenge | Fix `approvePhone` to recompute the intent (app). Seed in view (contract v0.2) |
| **App uninstalled / data cleared** | Wallet stranded | Recover with backup cards |
| **Forgotten / lost fingerprint** | Screen lock still works | Strict mode users recover with backup cards |

The honest limit: a live relay attack (an attacker relays your card's radio
to their phone in real time while you also approve on your phone) is not
fixable by any of this. See [DESIGN.md](DESIGN.md) section 2.

---

## 4. Interface plan

### 4.1 Navigation (today's 3-tab glass bar, plus one new hub)

```
Home ── Activity ── Settings
  │                   └── Security Center (new)
  │                         ├── Keys: phone, cards, guardian
  │                         │     ├── Add backup card
  │                         │     ├── Remove a card ("I lost this card")
  │                         │     └── Add guardian phone (later)
  │                         ├── Protection: limits, delays, hold timer, strict mode
  │                         ├── Integrity check
  │                         └── Alerts (watchtower)
  └── Send ─ Review ─ Phone prompt ─ Hold card ─ Result
```

### 4.2 Home: security status pill

Add one line under the balance. It is the user's at-a-glance hardware-wallet
status, and tapping it opens Security Center.

```
┌─────────────────────────────────┐
│  Wallet 3                 ⌄     │
│  12.40 NEAR                     │
│  ● Protected · 2 cards · phone  │  ← green
│  ┌───────────────────────────┐  │
│  │  ▢ DISCOVER  •••• 4821    │  │
│  └───────────────────────────┘  │
│  Instant left today: 4.0 / 5 N  │
│  [ Send ]  [ Receive ]  [ ❄ ]   │  ← ❄ = Freeze, always visible
└─────────────────────────────────┘
```

Pill states (worst first wins):

| State | Colour | Text | Tap action |
|---|---|---|---|
| Frozen | blue | "Frozen · tap to unfreeze" | Unfreeze flow |
| Pending item not from this phone | red | "Unknown request · review" | Activity filtered to it |
| Integrity check failed | red | "Wallet check failed" | Integrity screen |
| No backup | amber | "No backup card · add one" | Add backup card |
| All good | green | "Protected · N cards · phone" | Security Center |

### 4.3 Security Center

```
┌─────────────────────────────────┐
│ ← Security                      │
│                                 │
│ Protection score   ●●●●○        │
│                                 │
│ KEYS (2 of 3 needed)            │
│  📱 This phone   TEE · strict   │
│  💳 Discover 4821  main card    │
│  💳 Visa 0093     backup  ⋯     │
│  [ + Add backup card ]          │
│                                 │
│ PROTECTION                      │
│  Instant limit        5 NEAR    │
│  Daily limit         20 NEAR    │
│  Big transfer wait      1 h     │
│  Settings change wait   1 h     │
│  Recovery wait         24 h     │
│  Hold card to sign      5 s  ›  │
│  Fingerprint only       On   ›  │
│                                 │
│ CHECKS                          │
│  Wallet code     ✓ official     │
│  Account keys    ✓ none         │
│  Phone key       ✓ hardware     │
│  Alerts          ✓ every 15 min │
└─────────────────────────────────┘
```

Protection score, one point each: backup card added, strict mode on, alerts
on, instant limit ≤ 10% of balance, integrity check passed in the last 24 h.
Each missing point is a tappable row that fixes it.

Values shown under PROTECTION come from `get_wallet` (CHAIN), not from local
storage, so the screen always shows what the contract enforces.

### 4.4 Send: review, approve, hold

Four steps, one per screen state (the current Send screen already has the
keypad and slide to approve).

```
1 AMOUNT               2 REVIEW                      3 PHONE           4 CARD
┌──────────────┐     ┌──────────────────────────┐   ┌────────────┐   ┌──────────────┐
│ To: bob.test │     │ You send     2.00 NEAR   │   │ Fingerprint│   │  Hold card   │
│   2.00 NEAR  │ ──▶ │ To  bob.testnet          │──▶│ "Send 2.00 │──▶│   ◯ 3 s      │
│ [keypad]     │     │ ⚠ First time to this     │   │ NEAR to    │   │ Keep it still│
└──────────────┘     │   receiver               │   │ bob.testnet│   │ ▢▢▢▢▢▢░░░░   │
                     │ Arrives  now (in limit)  │   │ "          │   └──────────────┘
                     │  or: in 1 h, can cancel  │   └────────────┘
                     │ Left today after: 2.0 N  │
                     │ ═══▶ slide to approve    │
                     └──────────────────────────┘
```

Review screen rules (APP):
- Receiver shown in full, the last 6 characters in bold, never truncated.
- Warnings: first time to this receiver; receiver differs from an
  address-book entry by 1–2 characters; receiver came from the clipboard
  (show "Pasted · check it"); amount above 50% of balance.
- "Arrives" line uses `LimitInfo.isInstant` (same rule as the contract), so
  the user knows before signing whether there is a delay.
- The fingerprint prompt text (`KeystoreSigner.reason`) is built from the
  same `Action` object that `Domain.intentHash` hashes (invariant 10).

### 4.5 The hold-to-sign sheet (Tangem-style delay)

```
┌─────────────────────────────────┐
│           Hold your card        │
│                                 │
│            ╭──────╮             │
│            │  3   │  ← ring     │
│            ╰──────╯   fills     │
│   Discover •••• 4821 found      │
│   Keep the card on the phone    │
│                                 │
│   Card moved? Timer restarts.   │
│            [ Cancel ]           │
└─────────────────────────────────┘
```

Behaviour:
1. Card enters the field → app reads it (SELECT, records) and checks it is an
   enrolled card of this wallet (key hash match). Wrong card: "This isn't a
   card of this wallet" and stop, before any signature.
2. Countdown of N seconds. Every 500 ms the app sends a harmless command
   (re-SELECT of the application) to make sure the card is still there.
   `TagLostException` → ring resets, "Card moved, hold it again".
3. At zero, and only then, the app asks the card to sign the challenge.
4. Haptic tick each second, long buzz when done.

Settings: Off / 3 s / 5 s (default) / 10 s. Changing it needs a fingerprint.
Optional: only for over-limit payments and settings changes.

Be honest in the UI text: "Hold timer: a pause so nothing signs by accident.
Your real protection is the phone approval and the limits." This is an APP
feature: a skimmer with their own reader doesn't wait, and Tangem's own
card-side timer was bypassed (Ledger Donjon, 2023). Keep 5 s as the default
until we have tried real cards: a long hold makes NFC drops more likely.

### 4.6 Notifications (watchtower)

```
┌──────────────────────────────────────────┐
│ Tapvault · Wallet 3                       │
│ A card started a request you didn't make │
│ Send 5 NEAR to x7f.testnet · runs in 58m  │
│ [ Cancel it ]   [ Freeze wallet ]         │
└──────────────────────────────────────────┘
```

Both buttons open the app and need a fingerprint (cancel and freeze with the
phone are P-256 signatures).

---

## 5. User flows

Each flow lists the screens, what the user does, and what the chain does.
Delays are the app's values (`Delays.SHORTEST`): transfer 1 h, config 1 h,
recovery 24 h.

### 5.1 Create a wallet (today + one new step)

1. Welcome → Limits (instant, daily) → Setup progress (existing).
2. Tap main card (enrolment) → deploy → add card → finalize (existing).
3. **New: "Add a backup card now?"** screen right after finalize:
   "Without a backup, losing your phone or card loses this wallet."
   [Add backup card] [Later]. "Later" sets the amber pill on Home.

Why after finalize and not during setup: `setup_add_signer` would add it
without a delay, but setup would then need a second card at hand. Offering it
right after keeps setup short; the config delay is 1 h.

> Option for later: offer the backup card *inside* setup, using
> `setup_add_signer` with a `Possession::Card` proof, so it's added with no
> delay and with the contract's proof of possession. Same contract, no new
> wasm. This is better security (gap 8 doesn't apply during setup).

### 5.2 Send (today + review + hold)

1. Send → amount + receiver → **Review** (4.4) → slide to approve.
2. Fingerprint (TEE) → phone signs locally computed intent → `propose`.
3. **Hold card** (4.5) → card signs sealed challenge → app verifies the
   signature against the enrolled key → `approve_card` (+ `execute` if
   instant).
4. Result: "Sent" or "Approved, runs in 1 h · Cancel".

Any enrolled card of the wallet can be the one tapped. The app tries the
cards' recipes in order of key hash match (the card read in step 1 of 4.5
tells which one it is).

### 5.3 Add a backup card (new, CHAIN supports it today)

```
Security Center → + Add backup card
  1. Explain: "A second bank card that can co-sign with this phone.
     Keep it at home, not in the same wallet as your main card."
  2. Hold the new card → app reads its chain, recovers its key, checks
     it isn't already a signer, makes it sign a test challenge and
     verifies the signature locally (possession check, see below).
  3. Review: "Add Visa •••• 0093 as a backup card. Takes effect in 1 h.
     Needed approvals stay 2." → slide → fingerprint.
  4. Hold main card → approves.
  5. "Backup card added in 1 h" with countdown; Activity shows it;
     after 1 h the app (or the user) taps Execute.
```

On chain: `propose(Config{ops:[AddSigner(EmvCard{...})]})` by the phone,
`approve_card` by the main card, `execute` after `delays.config` (1 h)
because a P-256 approval is present.

Important details:
- **Possession check is APP-side.** After setup, `AddSigner` has no
  proof of possession in the contract (DESIGN gap 8, `apply_config` only runs
  `validate_config`). The app must therefore make the new card sign
  `domain::card_enroll_challenge`-style data and verify it with
  `Rsa.verifySdad` before proposing. Since the key comes straight from the
  card, a wrong key is unlikely, but a card that can't sign would become
  a useless signer.
- Store the new card's `CardRecipe`, network and last 4 per signer (today
  `Store` holds one card: `cardRecipe`, `cardNetwork`, `cardLast4`). Needs a
  small list in `Store`, keyed by key hash.
- Any config change bumps `signer_epoch` and voids other open proposals:
  warn "Pending payments will need to be approved again" if any are open.
- Threshold stays 2. With phone + 2 cards: phone + any card pays; two cards
  can only change settings, with the 24 h recovery delay.
- `MAX_SIGNERS` is 8, so up to 7 cards + phone. Recommend 1 backup:
  each card is another skimming target (DESIGN section 4).

### 5.4 Lost or stolen card (new)

```
Security Center → card row → ⋯ → "I lost this card"
  1. "Freeze the wallet first?" [Freeze now] (recommended if stolen)
  2. Review: "Remove Discover •••• 4821. Takes effect in 1 h."
  3. Fingerprint → phone proposes RemoveSigner(key_hash)
  4. Hold the other card → approves
  5. After 1 h: Execute. If frozen, include Unfreeze in the same
     proposal (one config proposal, two ops) so one wait covers both.
```

The contract rule "a card can't cancel its own removal" (DESIGN gap 9)
means the thief's card can't stop this. Block the flow if it would leave
fewer signers than the threshold (the contract refuses it anyway with
`ERR_INVALID_THRESHOLD`).

### 5.5 Lost phone: recover on a new phone (new, CHAIN supports it today)

Needs two cards of the wallet.

```
New phone, Welcome → "I have a wallet" → "Recover a wallet"
  1. Enter the wallet account id (or scan the QR from the old Receive
     sheet, which the user may have saved).
  2. App reads get_wallet: lists the cards (network + last 4 are not on
     chain, so it shows "Card 1, Card 2" and matches by tap).
  3. App creates a new phone key (TEE) and a new fee account (faucet /
     sponsor).
  4. Hold card A → proposes Config{ AddSigner(new phone),
     RemoveSigner(old phone) } with a card proof.
  5. Hold card B within 10 minutes → approves.
  6. Screen: "Recovery in 24 h. If your old phone is found, it can cancel
     this." Countdown; keep the app installed.
  7. After 24 h: Execute (anyone can send it). Wallet opens normally.
```

On chain: two card approvals, no P-256 approval → `delays.recovery`
(24 h). The old phone, if the owner still has it, can cancel: that is what
stops a thief holding two skimmed cards.

Details:
- Both cards must tap within `CARD_APPROVAL_WINDOW` (10 min) of the seal
  (DESIGN gap 10). Tell the user before step 4 to have both cards ready.
- Any card that the proposal doesn't remove can cancel it (`guard.rs`
  `cancel`). If a card was stolen together with the phone (bag stolen), the
  recovery proposal must also `RemoveSigner` that card, or the thief can keep
  cancelling the recovery with it. So step 2 asks "Was a card lost too?"
  and, if so, the user needs two *other* cards (a third card or a guardian).
- Each card's recipe is re-learned on the new phone by reading the card
  (no PIN, no stored data needed).
- Larger flow (new entry screen, new setup path): phase 2.

### 5.6 Freeze and unfreeze (today, promoted in the UI)

- Freeze: ❄ on Home, in notifications, in the lost-card flow. Phone
  (fingerprint) or any card (tap). Immediate (CHAIN).
- Unfreeze: config proposal with `Unfreeze`, 1 h with phone approval
  (existing `requestUnfreeze`).

### 5.7 Integrity check (new)

Runs on app start (at most every 6 h) and from Security Center:
1. `view_account` → `code_hash` equals the shipped build's base58 (or the
   known previous build, shown as "older build, still yours").
2. `view_access_key_list` → empty.
3. `get_wallet` → signers include this phone's public key; threshold and
   delays match what Security Center displays; not in setup mode.
Fail → red pill, explain which check failed, offer Freeze.

### 5.8 Strict mode (new, TEE)

The Keystore key's auth rule is fixed when the key is created
(`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or
AUTH_DEVICE_CREDENTIAL)` in `Keystore.kt`). So:
- **New wallets**: offer "Fingerprint only" in setup; create the key with
  `AUTH_BIOMETRIC_STRONG` only.
- **Existing wallets**: turning it on = create a second, strict phone key,
  then one config proposal `AddSigner(strict key) + RemoveSigner(old key)`,
  approved by the old phone key + card, after 1 h. Same machinery as 5.3.
- Warn: "If your fingerprint stops working, you'll need your backup cards."
  Require a backup card before enabling it.

### 5.9 Watchtower (new, APP)

- `WorkManager` periodic job (15 min minimum on Android), plus a check on app
  open. Reads `list_proposals` for every wallet on the phone.
- Alerts on: any open proposal whose approvals don't include this phone's
  signer index (somebody else started it); the wallet becoming frozen; a
  config change executed; an integrity check failure.
- Keeps the last seen proposal id per wallet in `Store` so it alerts once.
- Limitation to state in the UI: the alert goes to this phone. If the phone
  is the stolen thing, alerts need a guardian phone (phase 3) or a second
  device with the wallet in view-only mode.

---

## 6. Changes by file

### App only (same contract, same wasm hash; works for existing wallets)

| Feature | Files |
|---|---|
| Security Center screen, status pill | `Screens.kt` (new `SecurityScreen`, `Home` pill), `AppViewModel.kt` (`Screen.Security`, state), `Components.kt` |
| Review screen + warnings | `Screens.kt` (`Send`), `Store.kt` (address book: receiver → first-seen time) |
| Hold-to-sign | `Components.kt` (`TapSheet` ring), `AppViewModel.withCard` (countdown + presence check), `Emv.kt` (`CardService`: split "read + identify" from "sign" so the wait sits between them), `Store.kt` (`holdSeconds`) |
| Backup cards | `AppViewModel.kt` (`addBackupCard`, `removeCard`), `Wallet.kt` (`proposeConfig` helper if missing), `Store.kt` (card list per key hash instead of one card), `Screens.kt` |
| Several cards for signing | `AppViewModel.send`/`approveWithCard`: pick the signer index from the tapped card's key hash, not a fixed index |
| Integrity check | `NearClient.kt` (`view_account`, `view_access_key_list` already used in setup), `AppViewModel.kt` |
| Watchtower | new `Watchtower.kt` (WorkManager worker), `AndroidManifest.xml` (`POST_NOTIFICATIONS`), `Store.kt` (last seen ids) |
| Screen privacy, app lock | `MainActivity.kt` (`FLAG_SECURE`; on resume after 60 s in background, require fingerprint to show balances) |
| Gap 6b fix | `Wallet.kt` (`TapvaultWallet.approvePhone`: recompute `Domain.intentHash` from the action, refuse if it differs from the view) |
| Strict mode | `Keystore.kt` (`create(strict = true)`), setup option, 5.8 rotation flow |
| Recover on a new phone | `Onboarding.kt` (Recover entry), `AppViewModel.kt`, `Wallet.kt` (card-proposed config) |

Kotlin `ConfigOp.AddSigner`, `RemoveSigner`, `SetThreshold` already exist in
`Model.kt` with matching Borsh, so none of this changes signed bytes or
`vectors.json`.

### Contract v0.2 (new wasm; only wallets created after it get it, invariant 4)

| Change | Why |
|---|---|
| `AddSigner` requires the new key to sign during the delay (gap 8) | Contract-side proof of possession |
| Return the challenge seed in `ProposalView` (gap 6a) | App can verify what the card signs |
| Receiver allowlist (`ConfigOp::AllowReceiver`, delayed to add, instant to remove) | Allowlisted receivers within limit pay instantly; new receivers always wait. Hardware-wallet-grade defence against "phone + card + PIN stolen" |
| Optional `delays.new_receiver` | Same idea without an explicit list |
| `UpgradeCode` behind recovery delay (gap 12) | Let wallets receive fixes |

Every contract change follows "Shipping a contract change" in AGENTS.md
(three hash places, vectors, sandbox, live tests).

---

## 7. Build order

Deadline 14 Nov 2026 23:59 UTC. Ordered by security gained per hour.

| Phase | Items | Effort | Contract change |
|---|---|---|---|
| **1 (hackathon)** | Add backup card (5.3), several cards can sign, Security Center (read-only rows), Home pill, hold-to-sign (4.5), `FLAG_SECURE` | ~1 day | No |
| **2** | Lost card (5.4), integrity check (5.7), review warnings (4.4), gap 6b fix, watchtower (5.9), app lock | 2–3 days | No |
| **3** | Recover on a new phone (5.5), strict mode (5.8), backup card during setup, guardian phone | 3–5 days | No |
| **4** | Contract v0.2: possession proof, seed in view, receiver allowlist, upgrade path | 1 week + audit | Yes |

Phase 1 acceptance:
- With simulated cards (`SimCard`) on live testnet: add a second card,
  then pay with phone + card 2, then remove card 1. Extend
  `TestnetE2ETest` for this.
- On the phone: hold timer resets when the card is lifted; a wrong card is
  rejected before any signature.
- `android/build-apk.sh` passes the wasm hash check unchanged.

---

## 8. What we must not do

- **No bank-card PIN as the access code.** Offline PIN `VERIFY` uses the
  bank's PIN counter (a few wrong tries in Tapvault could block the real bank
  card), returns an unsigned "OK" the contract can't verify, and many
  contactless cards don't support it. The phone's fingerprint is the access
  code.
- **No app-only PIN presented as hardware security.** A PIN checked by our
  app is skipped by anyone running their own client; if added at all, it's
  a screen lock, labelled as one.
- **Never store or log the PAN** to show card details for more cards
  (invariant 5): only network + last 4 + key hash.
- **No mainnet** (invariant 7), and no real funds before an audit (DESIGN
  gap 19).

---

## Sources

- Tangem security features: https://tangem.com/en/blog/post/tangem-security-features
- Tangem access code: https://tangem.com/en/help-center/security/all-about-access-code/
- Tangem backup: https://tangem.com/en/help-center/security/how-backup-works/
- Xaman / Tangem 15 s hold: https://help.xaman.app/app/xumm-tangem-cards/how-safe-is-a-card
- Ledger Donjon, Tangem brute force: https://www.ledger.com/blog-brute-force-attack-tangem
- Tangem's response: https://tangem.com/en/blog/post/donjon-report
- Tangem SDK (`Card.kt` `securityDelay`, `Command.kt` `NeedPause`): https://github.com/tangem/tangem-sdk-android
- Tapvault security model: [DESIGN.md](DESIGN.md); contract API: [CONTRACT.md](CONTRACT.md)
