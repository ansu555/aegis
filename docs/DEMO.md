# Demo script (about 2 minutes)

Before recording:
- Android phone with NFC on, a screen lock and a fingerprint set up.
- A contactless debit/credit card. Run **Check a card** first: the report
  must end with "INTERNAL AUTHENTICATE works". If it doesn't, record with the
  demo card and say so.
- Install `android/app/build/outputs/apk/debug/app-debug.apk`.
- A second NEAR testnet account to send to (any `*.testnet` account works).
- Screen recording on the phone, plus a camera on the card for the taps.

## Shots

1. **Hook (10 s).** "This is my debit card. It's now the hardware wallet for
   my NEAR account. No seed phrase."
2. **Create the wallet (40 s).** Open Tapvault → *My contactless bank card* →
   limits 1 / 2 NEAR → *Create my wallet*.
   - Fingerprint: "Create your Tapvault wallet".
   - Tap the card: the app reads the card's certificate chain and the card
     signs its enrolment challenge. Card number never leaves the phone.
   - Fingerprint: "Add Visa card ****1234".
   - Fingerprint: "Lock the wallet…". Point out: the account's own key is now
     deleted. Only the contract can move funds.
3. **Pay (30 s).** *Send* → receiver, 0.5 NEAR → fingerprint shows the exact
   amount and receiver → *Tap your card* → "Sent". Open the explorer link:
   one transaction with `approve_card` + `execute`.
4. **Why it's safe (25 s).** Try 1.5 NEAR (over the instant limit): it's
   approved but waits an hour; show *Activity* → *Cancel*. Then *Freeze
   (card)*: one tap freezes everything.
5. **Close (15 s).** "A thief with my phone can't pay without my card. A
   thief with my card can't pay without my fingerprint. Even with both,
   limits and delays cap the damage. Built on NEAR."

## Talking points

- The card challenge is derived from block randomness captured **after** the
  payment is committed, so signatures skimmed from your pocket are useless.
- The phone key lives in Android's secure hardware and signs only after a
  fingerprint, every time.
- Delays run from full approval; anyone holding one of your keys can freeze.
- NEAR made this practical: verifying a P-256 phone signature in WASM costs
  ~32 Tgas and a card's RSA signature 3–21 Tgas, far under the 300 Tgas limit.
