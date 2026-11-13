# Demo script (about 2 minutes)

Updated 12 Nov 2026 for the current app. The app has **no demo or simulated
card any more**: every tap in the video is a real contactless bank card on a
real Android phone, on NEAR testnet. Record only with a card that showed
**Can sign** in Card check on the phone you record with. Nothing has been
tested with a real card yet ([STATUS.md](STATUS.md)), so run the real-phone
test first ([TESTING.md](TESTING.md#real-phone-test-plan)).

## Before recording

- **Phone:** Android 9 or newer, NFC on, a screen lock and a fingerprint, date
  and time set to automatic, Do Not Disturb on, vibration on (a short buzz
  marks the end of each card read).
- **APK:** built from the current code with `android/build-apk.sh`. Its
  `verifyWasm` check guarantees the app deploys the shipped contract (code hash
  `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`). Install fresh. If an older
  build made a wallet on this phone, uninstall it or use Settings →
  `Forget this wallet on this phone`: wallets created before 12 Nov run the previous
  contract build, which rejects Visa online signatures.
- **Card check first:** Welcome → **Check my card** → **Check a card**, hold
  the card you will use. It must show **Can sign**. Note its "Signs with" line
  for the voice-over, and tap **Share report** to keep the report.
- **Rehearse once end to end** on the same phone and note where the NFC
  antenna is (the tap sheet says "near the camera"; phones differ).
- **Receiver:** an existing testnet account; `friend.testnet` below stands for
  it (it is only the Send field's placeholder). The app refuses named
  accounts that don't exist.
- **Funding:** creating a wallet takes two faucet accounts (about 10 per IP per
  12 h, the rehearsal included). A faucet-funded wallet has about 6.6 NEAR to
  spend; a sponsor-funded one about 2.1, which still fits 0.5 + 1.5 but only
  just.
- **Cameras:** screen recording on the phone, plus a second camera on the back
  of the phone for the taps. Some phones black out the system fingerprint or
  PIN prompt in screen recordings; the second camera covers that.
- **Privacy:** cover the number, name and CVV on the physical card. The app
  shows only the last four digits (Card check, and the prompt
  `Add Visa card ****1234 as a signer`); blur them if you prefer.

Defaults used below: limits **1 NEAR at once, 2 NEAR per day** ("Instant
payment limit" and "Daily limit" on "Spending limits"). The app always sets the contract's shortest delays:
transfers 1 h, settings changes 1 h, recovery 24 h.

## Shots

| # | Time | On screen (exact labels) | Say |
|---|---|---|---|
| 1 | 10 s | The card in hand, then the Welcome screen: "Your card is your key." (swipe through the four slides) | "This is my debit card. It's now the hardware wallet for my NEAR account. No seed phrase, no new device." |
| 2 | 15 s (optional) | **Check my card** → **Check a card** → hold the card → badge **Can sign**, "Signs with" (Visa: `signature in GET PROCESSING OPTIONS (fDDA)`; Mastercard: `signature in GENERATE AC (CDA)`) → back arrow | "The phone reads the chip and checks its certificates up to the card network's key. Then the chip signs a challenge, the same kind of signature a terminal uses to check a card is genuine. No PIN, no payment, and the card number never leaves the phone." |
| 3 | 40 s | **Create wallet** → "Spending limits": "Instant payment limit" 1, "Daily limit" 2 → **Continue**. Setup screen "Setting up your wallet" with its progress ring (cut the account step if the faucet is slow). Sheet "Hold your bank card to the back of the phone to add it" → hold the card. Prompt "Create your Tapvault wallet" → fingerprint. Prompt `Add Visa card ****1234 as a signer` → fingerprint. Prompt "Lock the wallet: up to 1 NEAR at once, 2 NEAR a day" → fingerprint. Result "Your wallet is ready" / "Funds now move only with your phone and your card." | "The card is read and signs first, before anything is deployed. One approval deploys my wallet contract with my phone key, one adds the card, one sets the limits and deletes the account's own key. From now on only the contract's rules can move these funds." |
| 4 | 10 s | **View on explorer** (in the banner) → the wallet account: no access keys, the contract, the three setup transactions | "No keys left on the account: not even mine." |
| 5 | 30 s | Home → **Send** → "To" `friend.testnet`, amount `0.5` ("Goes out instantly") → **Review** → **Slide to approve** → prompt "Send 0.5 NEAR to friend.testnet" → fingerprint → "Waiting for the card challenge" → sheet "Tap your card to approve sending 0.5 NEAR" → hold the card → "Sent 0.5 NEAR" → **View on explorer** | "The prompt names exactly the amount and the receiver my phone approves. Then the contract picks the card's challenge from randomness in a later block, after my payment is fixed, so a signature skimmed from my pocket earlier can't match. One transaction: the card's approval and the transfer." |
| 6 | 25 s | Back → **Send** `1.5` ("Waits 1 h · you can cancel it") → **Review** → **Slide to approve** → fingerprint → tap → "Approved, waiting 1 h". **Activity** (tab bar) → "Approved · can execute in 0 h 59 min" → **Cancel** → prompt "Cancel: Send 1.5 NEAR to friend.testnet" → "Cancelled". **Wallet** → **Freeze** → "Freeze the wallet?" → **Freeze** → tap → "Wallet frozen", pill "Frozen" | "Over my limit it doesn't block, it waits an hour, counted from full approval, and I can cancel it. Any one of my keys can freeze everything; with the card it is one tap. Unfreezing needs both, plus a delay." |
| 7 | 15 s | Home, frozen | "My card alone can't pay: every payment also needs my phone's key, which signs only after my fingerprint or screen lock. My phone alone can't pay without my card. And even with both, only the daily allowance goes out at once. Built on NEAR." |

Shots 1–7 run about 2.5 minutes; drop shots 2 and 4 for a 2-minute cut.

## What to show on the explorer

All links come from the app's **View on explorer** (testnet.nearblocks.io).

- **Wallet account** (`/address/<wallet>`): the balance, **no access keys**,
  a deployed contract. Its first transactions: deploy + `new` (from the wallet
  account to itself), `setup_add_signer`, `setup_finalize` (which deletes the
  key it was signed with). To show the code hash, check it beforehand:
  `view_account` must return `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`
  (command in [TESTING.md](TESTING.md#4-check-the-wallet-on-the-explorer)).
- **The payment** (the link in "Sent 0.5 NEAR"): signed by the fee account
  `tv-…-gas…`, sent to the wallet account, two function calls `approve_card`
  and `execute`, three logs from the wallet account
  (`EVENT_JSON:{"data":[{"id":"<n>","signer_index":1}],"event":"approved","standard":"tapvault","version":"1.0.0"}`,
  then `"event":"ready"` and `"event":"executed"`; `<n>` is the proposal
  number, `0` for a new wallet's first payment), a 0.5 NEAR transfer receipt
  to the receiver and the `on_transfer` callback.
- **Optional, the commit step:** the `propose` transaction just before it (in
  the wallet's transaction list). It logs `proposal_created`, and its
  `seal_challenge` receipt runs in a **later block** and logs
  `challenge_sealed` with the card challenge. That is "commit, then challenge"
  on-chain.

## Talking points (accurate as of 12 Nov 2026)

- **Commit, then challenge.** `propose` fixes the payment; `seal_challenge`
  runs in a later block and stores that block's random seed; the card signs
  `H(card-approve ‖ intent ‖ seed)`. A signature collected earlier can't match,
  and a card must approve within 10 minutes of the seal.
- **The phone key** is a P-256 key in the Android Keystore (TEE). It can't be
  exported and signs only after a fresh fingerprint or screen-lock check, every
  time, under a prompt that names the amount and the receiver.
- **The card** is the EMV chip's own RSA key. The phone verifies the card's
  certificate chain; only the card's public key goes on-chain; the card number
  never leaves the phone. Nothing is sent to the bank.
- **Cards alone never move funds:** every transfer needs a phone approval.
  Wallets made by the app need both, every time (threshold 2).
- **Limits don't block, they delay.** Within 1 NEAR per payment and 2 NEAR per
  day (an allowance that refills continuously) a payment executes at once.
  Anything bigger waits 1 hour, counted from full approval; meanwhile it can
  be cancelled or the wallet frozen. Nothing executes by itself: someone taps
  Execute after the delay.
- **Freeze:** any single key, phone or card tap. Unfreezing is a delayed change
  that needs both.
- **No keys on the account** after setup: only the contract's rules move the
  funds. A separate fee account pays the gas and can't move anything.
- **NEAR made it practical.** NEAR has no P-256 host function, so the contract
  verifies the phone's signature in WASM, about 32 Tgas of a 34.8 Tgas
  `propose`. A card approval (`approve_card`, RSA check included) costs
  2.7 Tgas with a 1024-bit card key and 21.3 Tgas with the largest EMV key
  (1984-bit, e = 65537). The most expensive step, deploy + `new`, is 56.4 Tgas
  of the 300 Tgas limit (sandbox measurements, 12 Nov).
- **Tested:** the contract and the app's client code ran end to end on
  testnet with simulated Visa-, Mastercard- and DDA-style cards and a software
  phone key. Add what the real-phone test showed once it has run.

## Don't claim

- Support for card brands or banks beyond what Card check showed. RuPay is
  unknown until a RuPay card passes.
- "Audited": there was an internal review, not a professional audit.
- "A stolen phone can't do anything": malware on the phone plus your card held
  to a reader at the same moment (live relay) can approve a payment; limits,
  delays and freeze are the defence ([DESIGN.md](DESIGN.md)).
- "Recoverable": a single-card wallet can't be recovered without its phone key.
- "Delayed payments go through automatically": they need Execute.
- "The card doesn't notice": each tap is a real, abandoned EMV transaction on
  the chip (its counter goes up), though nothing reaches the bank.
- iPhone support (iOS doesn't let apps talk to payment cards) or mainnet
  (testnet only).

## If something goes wrong while recording

- **The card isn't read:** the sheet explains ("The card moved away too
  soon. …"); hold the card still on the known antenna spot until the sheet
  closes.
- **The faucet is slow or refuses:** create the wallet before recording and
  start the take at Home (use the rehearsal's setup clip for shot 3), or use an
  APK built with the sponsor.
- **A setup step fails:** **Continue setup** resumes; finished steps are skipped.
- **After a take the wallet is frozen:** Home → **Request unfreeze**, then
  Activity → **Execute** after 1 hour; or Settings → `Forget this wallet on
  this phone` and make a new wallet (two more faucet accounts; the old
  wallet's test NEAR stays locked).
- **No card passes Card check:** don't fake a tap. Show the honest Card check
  result, then the live testnet run of `TestnetE2ETest` with its explorer
  links, and say clearly that its card is simulated.

## After recording

The submission needs a public post on X with the video, tagging `@NEARProtocol`
with `#NEARHackathon`, before 14 Nov 2026, 23:59 UTC ([STATUS.md](STATUS.md)).
