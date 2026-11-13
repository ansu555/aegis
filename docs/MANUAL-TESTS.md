# Manual tests (what needs you, a phone and real cards)

What the automated tests already cover, and what only you can check: a real
phone, real bank cards, real NFC, the fingerprint prompt, and anything that
needs waiting. One section per phase of
[HARDWARE-WALLET-PLAN.md](HARDWARE-WALLET-PLAN.md). Step-by-step detail for
the older flows (setup, send, freeze) is in [TESTING.md](TESTING.md).

Mark each line `PASS`, `FAIL` (with what you saw) or `SKIP`.

## Before you start

- Build and install on the **same computer as before** (a different debug key
  makes Android refuse the update; uninstalling deletes the phone key and
  strands the wallet):
  ```bash
  android/build-apk.sh
  adb install -r android/app/build/outputs/apk/debug/app-debug.apk
  ```
- Two contactless bank cards that show **Can sign** in Card check (Settings →
  Your card → Check a card). A third card of any kind for wrong-card tests.
- **Screenshots come out black on purpose** (`FLAG_SECURE`). Photograph the
  screen with another device when something fails.
- On a failure send: the photo, Settings → Advanced → Technical log (photo),
  and `adb logcat -b crash -d` if the app closed.

---

## Phase 1: several cards, backup card, Security Center, hold-to-sign

### Already tested automatically (13 Nov)

| What | Where | Result |
|---|---|---|
| Tapped card identified among two by key hash, then signs for that key (DDA, Visa-style, Mastercard-style) | `MultiCardTest` | Pass |
| Card that isn't the wallet's refused **before** it signs (zero signatures on DDA/CDA) | `MultiCardTest`, live E2E | Pass |
| Card lifted during the hold is noticed, nothing signed | `MultiCardTest` | Pass |
| Backup-card possession check (wrong challenge, wrong key, changed signature all refused) | `MultiCardTest`, live E2E | Pass |
| Card already a signer refused as backup, before it signs | `MultiCardTest`, live E2E | Pass |
| Old single-card store migrates to entry 1 and binds to setup's card | `MultiCardTest` | Pass |
| `AddSigner(card 2)` proposed by phone, approved by card 1, refused before the 1 h delay, on live testnet | `TestnetE2ETest` ×3 card kinds | Pass |
| After the delay: AddSigner executes, voids open payments, phone + card 2 pays, card 1 removed and refused, card 2 pays at its new index | `TestnetE2ETest` with `TAPVAULT_WAIT_DELAYS=1` | see [STATUS.md](STATUS.md) |
| APK builds, contract hash unchanged | `android/build-apk.sh` | Pass |

### You need to test (phone + real cards)

**A. Upgrade an existing wallet (migration)** — do this first, before
creating anything new.

| # | Do | Expect | Result |
|---|---|---|---|
| A1 | Install the new APK over the old one (`adb install -r`) | App opens on Home, same wallet, same balance | |
| A2 | Look at the card on Home | Same network and last 4 digits as before | |
| A3 | Pull to refresh, then Settings → Advanced → Technical log | A line `card list migrated` (once) | |
| A4 | Settings → Security Center | Your card listed by name ("Visa •••• 1234 · Main card"), not "Card 1" | |
| A5 | Send 0.1 NEAR with the enrolled card | Countdown, then **Sent 0.1 NEAR** | |

**B. Hold-to-sign**

| # | Do | Expect | Result |
|---|---|---|---|
| B1 | Send 0.1 NEAR, approve the prompt, hold the card | "Hold your card", "`<card>` found", ring 5→1 with a light tick each second, "Signing", long buzz, **Sent** | |
| B2 | Same, lift the card at ~2 s left | "Card moved, hold it again. The timer starts over."; hold again → restarts at 5; **exactly one** payment | |
| B3 | Same, tap **Cancel** during the countdown | "Card tap cancelled" + "Payment #n is saved…"; nothing sent; cancel it in Activity | |
| B4 | Security Center → Hold card to sign → `3 s` | Fingerprint prompt "Set the card hold timer to 3 s"; row shows `3 s`; next payment counts 3 | |
| B5 | Set `Off` | Next payment signs with no countdown | |
| B6 | Set `10 s`, pay | Does the card stay connected 10 s? (NFC drops on long holds are the risk) | |
| B7 | Set back to `5 s`; Home → Freeze (card) | **No** countdown for freeze; wallet frozen. Then Request unfreeze (countdown again) | |

**C. Wrong card is refused before signing**

| # | Do | Expect | Result |
|---|---|---|---|
| C1 | Send 0.1 NEAR, hold a **bank card that isn't in the wallet** | Sheet comes back: "That card isn't one of this wallet's cards. Tap a card you added." — **no countdown starts**. Log: "…it signed nothing" | |
| C2 | Then hold the right card | Countdown, **Sent 0.1 NEAR**, one payment only | |
| C3 | Hold a card of a network the app has no CA key for (if you have one) | "couldn't read the card's key (…)" + "Payment #n is saved…" | |

**D. Add a backup card** (needs 1 h wait)

| # | Do | Expect | Result |
|---|---|---|---|
| D1 | Home: pill under the balance | Amber "No backup card · add one"; tapping opens Security Center | |
| D2 | Security Center rows | Keys "2 of 2 needed"; This phone (TEE/secure hardware); main card; instant + daily limit as you set; waits 1 h / 1 h / 24 h; hold 5 s; Wallet Active | |
| D3 | Add backup card → Hold the new card → hold the **main** card | "That card is already one of this wallet's cards…" | |
| D4 | Again with the **second** card | Review: its network + last 4, "Takes effect in 1 h", "Approvals needed Stay 2", "Test signature Verified on this phone" | |
| D5 | Have a payment waiting in Activity first, then repeat D4 | Review also shows "Waiting requests will be voided" | |
| D6 | Slide to add → fingerprint | Prompt says "Add `<card>` as a backup card (takes effect after 1 h)" | |
| D7 | Tap sheet: hold the **new** card first | "That card isn't one of this wallet's cards" (not a signer yet) | |
| D8 | Hold the main card | Countdown, then **Backup card added in 1 h** | |
| D9 | Security Center / Activity | "Adding `<card>`" with countdown; Activity shows "Add backup card" | |
| D10 | After 1 h: Activity → Execute | **Done**; Security Center lists the backup card; Keys "2 of 3 needed"; Home pill green "Protected · 2 cards · phone"; the payment from D5 shows "Void: signers changed" | |

**E. Pay with either card**

| # | Do | Expect | Result |
|---|---|---|---|
| E1 | Send 0.1 NEAR with the **backup** card | Countdown shows the backup card's name; **Sent**; Log "`<card>` identified (signer 2)" | |
| E2 | Send 0.1 NEAR with the **main** card | Still works (signer 1) | |
| E3 | Freeze with the backup card | Frozen, no countdown | |

**F. Other**

| # | Do | Expect | Result |
|---|---|---|---|
| F1 | Take a screenshot in the app | Black image (or "can't take screenshot") | |
| F2 | Recent apps screen | Tapvault's preview is blank | |
| F3 | Create a **new** wallet (Settings → My wallets → Add a wallet) | Setup works as before; its card shows by name in Security Center right away (no migration line) | |
| F4 | Home pill red "Unknown request · review" | Only shows if a card started a request; hard to trigger with the app alone — SKIP unless you test with a second phone | |
| F5 | No crashes during all of the above | | |

---

## Phase 2: lost card, wallet check, review warnings, alerts, app lock

### Already tested automatically (13 Nov)

| What | Where | Result |
|---|---|---|
| Approve/cancel from Activity: the phone recomputes the request's hash from its details and refuses a mismatch (other receiver, other id, other wallet, unreadable request) | `Phase2Test` | Pass |
| Every kind of request reads back from the node's JSON to the exact bytes that were signed | `Phase2Test` | Pass |
| Wallet check verdicts: shipped build OK, previous build "older build, still yours", wrong code / account key present / phone key missing / setup mode each fail | `Phase2Test` | Pass |
| Wallet check against a real testnet wallet (code hash, no account keys) | `Phase2Test` live | Pass |
| Watchtower: alerts once for a request this phone didn't approve, a freeze, a signer change, a failed check; nothing on the first look or for void requests | `Phase2Test` | Pass |
| Look-alike receivers (1–2 letters different), first-time receivers | `Phase2Test` | Pass |
| App compiles and assembles | Gradle | Pass |

### You need to test (phone + real cards)

**G. App lock**

| # | Do | Expect | Result |
|---|---|---|---|
| G1 | Start the app fresh (swipe it away first) | "Tapvault is locked" + fingerprint prompt; balance not visible behind it | |
| G2 | Unlock | Home as usual | |
| G3 | Home button, come back within 30 s | No lock | |
| G4 | Leave the app 2 min, come back | Locked again | |
| G5 | Cancel the prompt | Lock screen stays; **Unlock** button shows the prompt again | |
| G6 | During a payment, approve with the **screen lock** (PIN) instead of a fingerprint | Payment continues, no lock screen pops up in the middle | |

**H. Send review warnings**

| # | Do | Expect | Result |
|---|---|---|---|
| H1 | Send to an account you have never paid from this phone | Review: "First payment to this receiver from this phone." The receiver in full, last 6 letters bold | |
| H2 | Pay it 0.1 NEAR, then start another payment to it | No first-time warning | |
| H3 | Type the same account with one letter changed (e.g. `l` → `1`) | "Looks like `<account>`, which you paid before, but isn't the same account" | |
| H4 | Use the **Paste** button for the receiver | "Pasted · check it" | |
| H5 | Paste from the keyboard's clipboard suggestion instead | Also "Pasted · check it" | |
| H6 | Amount more than half the balance | "More than half of this wallet's balance." | |
| H7 | Amount within the instant limit | "Arrives: Now (within your limit)", "Instant left today after" = left − amount | |
| H8 | Amount over the limit | "Arrives: In 1 h · you can cancel" | |

**I. Approve with the phone from Activity (gap 6b fix)**

| # | Do | Expect | Result |
|---|---|---|---|
| I1 | Start a payment, cancel the card tap (so it waits in Activity), then Activity → Approve · phone | Fingerprint prompt reads "Approve: Send 0.1 NEAR to `<account>`" (built on the phone) | |
| I2 | Activity → Cancel on a request | Prompt "Cancel: …" names the same request; it's cancelled | |

**J. Wallet check (Security Center → Checks)**

| # | Do | Expect | Result |
|---|---|---|---|
| J1 | Open Security Center | Checks: Wallet code ✓, Account keys ✓, Phone key ✓, Setup ✓; "Last check … ago" | |
| J2 | Tap **Check now** | Banner "Wallet check passed" | |
| J3 | A wallet made **before 12 Nov** (if you still have one) | Wallet code shows as a note "older build", not a failure; pill stays green | |
| J4 | Protection score row | Dots and "Missing: …" list match reality (backup card, alerts, limit ≤ 10% of balance, check in 24 h, not frozen) | |

**K. Alerts (watchtower)** — needs a second device or patience

| # | Do | Expect | Result |
|---|---|---|---|
| K1 | Security Center → Alerts | Android 13+: permission prompt; row shows **On** | |
| K2 | Freeze the wallet **with a card** (Home → Freeze), close the app, wait up to 30 min | Notification "`<wallet>`: Wallet frozen"; tapping it opens Security Center | |
| K3 | Reboot the phone, repeat a change (e.g. request unfreeze from the app, then close it) | Alerts keep working after a reboot (job is persisted) | |
| K4 | Turn Alerts off | No more notifications | |
| K5 | "A request you didn't make": only a request **not approved by this phone** triggers it (e.g. started from another phone or a test script). SKIP if you can't make one | Notification with **Cancel it** (→ Activity) and **Freeze wallet** (→ Security) | |

**L. I lost this card** (needs a wallet with 2 cards, then 1 h)

| # | Do | Expect | Result |
|---|---|---|---|
| L1 | Wallet with one card: Security Center → tap the card | "This is the wallet's only card" + Freeze button; no removal offered | |
| L2 | Wallet with 2 cards: tap the backup card | Sheet: "Freeze the wallet first" **On**, Remove, Takes effect in 1 h, Approve with `<other card>`, "Also: Unfreezes when it executes" | |
| L3 | Slide to remove | Freeze prompt, wallet frozen; then prompt "Remove `<card>` from this wallet and unfreeze (takes effect after 1 h)" | |
| L4 | Tap sheet: hold the card being removed | "That's `<card>`, the card you're removing. Tap another card of this wallet." | |
| L5 | Hold the other card | **`<card>` removed in 1 h**; Security Center shows the card "Being removed · see Activity" | |
| L6 | After 1 h: Activity → Execute | Card gone from Security Center, wallet unfrozen, pill amber "No backup card" | |
| L7 | Pay with the removed card | "That card isn't one of this wallet's cards" | |
| L8 | Repeat L2 with Freeze **Off** | Only one prompt (removal), wallet stays active | |

## Phase 3

Not built yet.

## Phase 4 (contract v0.2)

Not built yet.
