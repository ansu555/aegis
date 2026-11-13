# Bank cards: EMV over NFC

How the app talks to a contactless bank card, how it makes the card sign the
wallet's challenge, which CA keys it trusts, what the contract checks, and how
to read a Card check report.

The code is the source of truth:

| File | What it holds |
|---|---|
| [`android/core/src/main/kotlin/app/tapvault/core/Emv.kt`](../android/core/src/main/kotlin/app/tapvault/core/Emv.kt) | `EmvSession` (one card session), `CardService` (`enrol`, `sign`, `inspect`), `CardRecipe`, `CardReport`, `CapkStore`, `Rsa` |
| [`android/core/src/main/kotlin/app/tapvault/core/Tlv.kt`](../android/core/src/main/kotlin/app/tapvault/core/Tlv.kt) | BER-TLV parser (`Tlv`) and Data Object Lists (`Dol`, `DolEntry`) |
| [`android/core/src/main/resources/capk.txt`](../android/core/src/main/resources/capk.txt) | The CA public keys the app trusts |
| [`android/app/src/main/kotlin/app/tapvault/MainActivity.kt`](../android/app/src/main/kotlin/app/tapvault/MainActivity.kt) | NFC reader mode and `IsoDepTransceiver` (the APDU link) |
| [`crates/tapvault-core/src/emv.rs`](../crates/tapvault-core/src/emv.rs) | What the contract checks (`verify_card_challenge`, `verify_sdad`, `EmvKeyLayout::validate`) |
| [`android/core/src/test/kotlin/app/tapvault/core/SimCard.kt`](../android/core/src/test/kotlin/app/tapvault/core/SimCard.kt) | The simulated card (tests only; the app has no simulated card) |

How the app gets a card in the first place (reader mode, taps, retries) is in
[ANDROID.md](ANDROID.md). User-visible errors and fixes are in
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Status

> **12 Nov 2026: no real bank card has been read by this code yet.** Card
> support is built from the EMV specifications and public captures of real
> Visa and Mastercard cards. Every card test so far used the simulated card.

| What | Evidence | Result |
|---|---|---|
| Certificate chain recovery (CA, issuer, card) and the static-data hash | `EmvTest` with `SimCard` (a made-up CA, issuer and card key) | Tested with the simulated card only |
| Classic DDA via INTERNAL AUTHENTICATE | `EmvTest`; `TestnetE2ETest` with `TAPVAULT_CARD_PROFILE=DDA` | Tested with the simulated card only |
| Visa-style fDDA (signature in the GPO answer, format 95, 19 bytes) | `EmvTest.visaStyleCardSignsFullFddaTerminalData`; `TestnetE2ETest` with `VISA_FDDA` | Tested with the simulated card only |
| Mastercard-style CDA (zero amount declined, then amount 100) | `EmvTest.mastercardStyleCardFallsBackToANonZeroAmount`; `TestnetE2ETest` with `MASTERCARD_CDA` | Tested with the simulated card only |
| SDAD check, formats `05` and `95` only | Rust: `crates/tapvault-core` unit tests and EMV vectors, `contract/tests/flows.rs` (`a_visa_card_signing_online_fdda_works_end_to_end`); Kotlin: `EmvTest.formatBytesOtherThan05And95AreRejected` | Tested |
| Reports and traces never contain the PAN | `EmvTest.reportsAndTracesNeverCarryTheCardNumber` | Tested with the simulated card only |
| `capk.txt` loads | `EmvTest.capkFileLoadsWithChecksummedKeys` (checks that Visa 09 and 10, Mastercard EF, RuPay 6B and 05 are present) | Tested; no real certificate has been checked against these keys |
| Real Visa, Mastercard or RuPay cards over a phone's NFC | none | Untested |
| RuPay signing method | none; NPCI's contactless specs aren't public | Unknown |

`SimCard` was written from the same reading of the specs as the app code, so
passing it proves the pieces fit together, not that real cards answer the same
way. The first real-card run is the plan in [TESTING.md](TESTING.md).

## EMV in brief (what the code relies on)

**Application, AID, RID.** A bank card holds one or more payment
applications. Each has an Application Identifier (AID, tag `4F`), for example
`A0000000031010`. Its first 5 bytes are the RID, which names the network:
`A000000003` Visa, `A000000004` Mastercard, `A000000524` RuPay
(`EmvSession.NETWORKS`; anything else is reported as `unknown`).

**PPSE and FCI.** A contactless card lists its applications in the PPSE, a
directory selected by the name `2PAY.SYS.DDF01`. Every SELECT answers with
File Control Information (FCI, template `6F`): the selected name (`84`) and a
proprietary template (`A5`) with the label (`50`), the priority (`87`), the
PDOL (`9F38`), and for the PPSE the directory (`BF0C`) of entries (`61`).

**TLV and status words.** Card data is BER-TLV: tag, length, value; templates
nest. Every answer ends in a 2-byte status word: `9000` is success, anything
else is an error (`swMeaning` turns the common ones into words, for example
`6A82` "not found", `6985` "conditions of use not satisfied", `6D00` "command
not supported").

**Data Object Lists.** A card tells the terminal which values to send with a
command by listing (tag, length) pairs:

| DOL | Tag | Used with | Where the card puts it |
|---|---|---|---|
| PDOL | `9F38` | GET PROCESSING OPTIONS | the application's FCI |
| CDOL1 | `8C` | the first GENERATE AC | a record |
| DDOL | `9F49` | INTERNAL AUTHENTICATE | a record; if missing, the default `9F37 04` (`EmvSession.DEFAULT_DDOL`) |

The app fills every requested entry with `EmvSession.terminalValue` (table
below) and concatenates the values in the card's order.

**GET PROCESSING OPTIONS (GPO).** Starts a transaction. The answer carries the
AIP and the AFL, either as format 1 (`80`: AIP then AFL) or format 2 (`77`
with `82` and `94`). On Visa cards it can also carry a signature (`9F4B`) and a
cryptogram.

**AIP (`82`).** Two bytes of capability flags. The code reads byte 1: `0x40`
SDA, `0x20` DDA, `0x01` CDA.

**AFL (`94`).** Which records to read: 4-byte entries of SFI (byte 1 shifted
right by 3), first record, last record, and how many of those records (from
the first) are part of the signed static data.

**READ RECORD and static data.** `READ RECORD` returns one record (template
`70`). The records named for offline authentication, concatenated, are the
*static data* the issuer signed into the card's certificate: for SFI 1 to 10
the value of the `70` template, for SFI 11 and up the whole record, plus the
AIP if the SDA Tag List (`9F4A`) is `82` (`EmvSession.readRecords`).

**Certificate chain.** The card's own RSA key (the ICC key) is certified by
its issuer, and the issuer's key is certified by the network's CA:

```
CA public key        capk.txt, found by RID + CA index (8F)
  | signs
issuer certificate   90 (+ remainder 92, exponent 9F32)   -> issuer public key
  | signs
ICC certificate      9F46 (+ remainder 9F48, exponent 9F47) -> card (ICC) public key
                     its hash also covers the PAN and the static data
```

Recovering a certificate means `cert^e mod n` with the signer's key. The app
checks both certificates on the phone (`EmvSession.recoverCardKey`) and only
the ICC public key goes on-chain.

**SDAD and the Unpredictable Number.** For dynamic authentication the card
signs data that includes a random number from the terminal, the Unpredictable
Number (UN, tag `9F37`, usually 4 bytes). The signature is the Signed Dynamic
Application Data (SDAD, tag `9F4B`, or tag `80` in an INTERNAL AUTHENTICATE
answer). Recovered with the ICC public key it looks like this:

```
6A | format | 01 | Ldd | ICC dynamic data (Ldd bytes) | BB .. BB | SHA-1 (20 bytes) | BC
SHA-1 input = recovered[1 .. n-21) || terminal dynamic data
format: 05 (DDA, CDA, offline fDDA) or 95 (Visa fDDA on an online transaction)
```

Tapvault puts the first bytes of its 32-byte challenge into the UN. A valid
SDAD over terminal data holding that UN proves the card signed this challenge.

**Cryptograms.** GPO (Visa) and GENERATE AC return an application cryptogram
(`9F26`) whose type is in the Cryptogram Information Data (`9F27`, top two
bits): `00` AAC (declined), `40` TC (approved offline), `80` ARQC (go online).
Tapvault never sends a cryptogram to anyone; it only needs the signature that
comes with it.

**TTQ (`9F66`) and `9F69` (Visa).** Visa cards ask for the Terminal
Transaction Qualifiers in the PDOL; they tell the card what the reader
supports and whether it requires an online cryptogram. Visa returns its Card
Authentication Related Data (`9F69`: fDDA version, a card random number, card
qualifiers) per transaction, either in the GPO answer or in the last AFL
record; fDDA version 01 signs over UN, amount, currency and `9F69`.

## The three signing methods

All three give the same kind of proof: an SDAD whose SHA-1 covers terminal
data containing the UN. The contract checks them the same way. `SignMethod`
(in `Emv.kt`) names them; the label is what the app shows.

| `SignMethod` | Label shown | What makes the card sign | Terminal data the SDAD covers | Format | Expected on |
|---|---|---|---|---|---|
| `DDA` | "INTERNAL AUTHENTICATE (DDA)" | `INTERNAL AUTHENTICATE` with the DDOL values | The DDOL values; with the default DDOL just the 4-byte UN | `05` | Cards that answer INTERNAL AUTHENTICATE over NFC (more common on the contact interface) |
| `FDDA` | "signature in GET PROCESSING OPTIONS (fDDA)" | The GPO itself (Visa qVSDC) | One of three forms, below | `95` online, `05` offline | Visa payWave |
| `CDA` | "signature in GENERATE AC (CDA)" | `GENERATE AC` with P1 bit `0x10` (CDA requested) | The UN only (EMV Book 2, 6.6.2) | `05` | Mastercard contactless and others |

### DDA (`EmvSession.dda`)

1. Reads the records if this session hasn't yet (the DDOL is in a record).
2. Builds the DDOL values with `terminalValue`; the UN is the first bytes of
   the challenge. `un_offset` is where `9F37` sits in the DDOL, `un_len` its
   length. A DDOL without `9F37` fails ("the card's DDOL has no Unpredictable
   Number").
3. Sends `INTERNAL AUTHENTICATE`; the SDAD is the value of `80` (format 1) or
   `9F4B` (format 2). No signature: "INTERNAL AUTHENTICATE returned no
   signature".

### Visa fDDA (`EmvSession.fdda`)

1. The GPO of the attempt already used the challenge as the UN and the
   plan's TTQ.
2. If the GPO answer lacks `9F69` or `9F4B`, the app sends a fresh
   `READ RECORD` of the last record named by the current AFL
   (`EmvSession.lastRecord`), because Visa puts this transaction's `9F69`
   there. A cached copy from an earlier GPO would be stale. If that read
   fails, the error is ignored and the attempt goes on without it.
3. Takes the SDAD from the GPO answer (or that record). None: "no signature in
   the GPO answer (<cryptogram>)".
4. Builds up to three terminal-data candidates from the PDOL values the GPO
   sent (`gpoSent`) and keeps whichever verifies:

| `CardRecipe` variant | Value | Terminal data | Typical length |
|---|---|---|---|
| `FDDA_FULL` | 0 | UN `9F37` + amount `9F02` + currency `5F2A` + `9F69` (fDDA version 01) | 4 + 6 + 2 + 7 = 19 bytes |
| `FDDA_AMOUNT` | 1 | UN + amount + currency | 12 bytes |
| `FDDA_UN` | 2 | UN only (fDDA version 00) | 4 bytes |

The UN is always at offset 0. The PDOL must contain `9F37` ("the card's PDOL
has no Unpredictable Number" otherwise).

TTQ: `37 80 40 00` (`TTQ_ONLINE`) requires an online cryptogram, so the card
answers with an ARQC and uses none of its offline counters; a Visa card then
signs with format `95`. `37 00 40 00` (`TTQ_OFFLINE_OK`) lets the card approve
offline (TC, format `05`). When the probe GPO already signed, enrolment tries
the online TTQ first (plan 1) and the offline one second (plan 2); when it
didn't, the offline TTQ goes first (plan 2) and the online TTQ with amounts
0 and 1.00 comes after the DDA and CDA plans (plans 7 and 8 below). The TTQ
is only sent if the PDOL asks for `9F66`, and plan 2 only exists then.
Byte 1 `0x37` = qVSDC, contact chip, online PIN, signature, and
"offline data authentication for online authorisations supported", so
online-only Visa cards may still sign. Byte 3 `0x40` = consumer-device CVM
supported.

### CDA (`EmvSession.cda`)

1. Reads the records if this session hasn't yet (CDOL1 is in a record). No
   CDOL1: "no CDOL1, so no GENERATE AC"; no `9F37` in it: "CDOL1 has no
   Unpredictable Number".
2. Sends `GENERATE AC` with the CDOL1 values: P1 `0x90` (ARQC + CDA) for
   variant `CDA_ARQC` (0), P1 `0x50` (TC + CDA) for `CDA_TC` (1). The amount
   is the plan's (0 or 100).
3. The SDAD is `9F4B`. None: "GENERATE AC answered without a signature
   (<cryptogram>)"; a card that declines a zero amount shows "declined: AAC".
4. Terminal data = the UN only, offset 0.

## Enrolment and Card check: one tap, command by command

`CardService.enrol(card, challengeFor)` is used by setup (the challenge is
`Domain.cardEnrollChallenge(wallet, 1, keyHash)`) and by the Card check
(`CardService.inspect`, the challenge is 32 random bytes and nothing is
saved). Both run exactly this code.

```
App (CardService.enrol / enrolWith)                              Card
  SELECT PPSE ------------------------------------------------------->
         <--------------------------------------------------------- directory: AIDs (4F), labels (50), priority (87)
  -- for up to 5 applications, best priority first --
  SELECT <AID> ------------------------------------------------------>
         <--------------------------------------------------------- FCI: label (50), PDOL (9F38)
  GPO (random UN, amount 0, TTQ 37804000) --------------------------->
         <--------------------------------------------------------- AIP (82), AFL (94) [+ 9F4B on Visa]
  READ RECORD for every record in the AFL --------------------------->
         <--------------------------------------------------------- 8F 90 92 9F32 5A 9F46 9F47 9F48 9F49 8C ...
  (phone) CA key -> issuer key -> card key, PAN and static-data checks
  (phone) card key within the contract's key rules
  (phone) challenge = challengeFor(card key)
  -- for each plan from EmvSession.plans, until one verifies --
  SELECT <AID>, then GPO (UN = challenge[0..un_len), plan's amount, plan's TTQ) -->
    fDDA: READ RECORD <last AFL record> (fresh) ------------------->  9F4B (in GPO answer), 9F69
    DDA:  INTERNAL AUTHENTICATE <DDOL values> --------------------->  SDAD
    CDA:  GENERATE AC P1=90 or 50 <CDOL1 values> ------------------>  9F27, 9F4B
  (phone) verify the SDAD with the card key
  -> Enrolment(key + layout, proof, report, recipe)
```

Step by step:

1. **Applications** (`EmvSession.applications`). `SELECT PPSE`; the `61`
   entries under `BF0C` are sorted by the low nibble of `87` (1 first; 0 or
   missing counts as 15). If SELECT PPSE fails, the app notes "no PPSE
   (...); trying known applications" ("unreadable PPSE (...); trying known
   applications" if the answer doesn't parse); a PPSE that lists no
   application falls back without a note. The fallback list is
   `KNOWN_AIDS`: `A0000000031010`, `A0000000041010`, `A0000005241010`,
   `A0000000043060`, `A0000000032010`. `enrol` tries the first five
   applications of the list (`take(5)`), so without a PPSE all five known
   AIDs are tried.
2. **Select** (`EmvSession.select`). Remembers the selected AID (the FCI's
   `84` if it is 5 to 16 bytes, else the AID asked for), the label and the
   PDOL. Selecting a different AID drops cached records and keys.
3. **Probe GPO** with a random UN and amount 0, online TTQ. This GPO only
   finds the AIP and AFL (and whether the card signs in its GPO answer).
4. **Read records** (`EmvSession.readRecords`), once per application, and
   assemble the static data. `gpoSigned` = a `9F4B` appeared in the GPO answer
   or a record.
5. **Recover the card key** (`EmvSession.recoverCardKey`):
   - CA index `8F`, issuer certificate `90`, issuer exponent `9F32`, issuer
     remainder `92` (optional), PAN `5A`. A missing tag fails with its own
     message (table in [Failures](#what-each-failure-means)).
   - CA keys for (RID, index) from `capk.txt`; each candidate is tried.
   - Issuer certificate: header `6A`, format `02`, trailer `BC`, hash
     algorithm `01`, issuer identifier matches the start of the PAN, SHA-1 over
     the certificate body, remainder and exponent. An expired issuer
     certificate only adds the note "issuer certificate expired MM/YY".
   - ICC certificate `9F46` (exponent `9F47`, remainder `9F48`): header `6A`,
     format `04`, trailer `BC`, the PAN in it matches `5A`, key length
     matches, SHA-1 over body, remainder, exponent and static data. The ICC
     certificate's expiry is not checked.
   - The ICC exponent must fit in 31 bits.
   - `enrolWith` then applies the contract's rules for a card key
     (`EmvKeyLayout::validate`): modulus 64 to 248 bytes (512 to 1984
     bits), first byte non-zero, odd; exponent 3 or 65537. A key that
     breaks one fails here, before any signing attempt, with "this card
     can't be a signer: <reason>" ([Failures](#what-each-failure-means)).
6. **Challenge.** `challengeFor` is called with a probe key
   `SignerKey.EmvCard(modulus, exponent, 0, 4, 4)`. The challenge may depend
   only on the key hash, which ignores the layout.
7. **Plans** (`EmvSession.plans(gpoSigned)`), tried in this order:

   | # | Plan (method, amount, variant, TTQ) | Added when |
   |---|---|---|
   | 1 | FDDA, 0, -, online | the probe GPO (or a record) carried a signature |
   | 2 | FDDA, 0, -, offline allowed | the PDOL asks for both `9F37` and `9F66` |
   | 3 | DDA, 0 | the AIP says DDA |
   | 4 | CDA, 0, ARQC | CDOL1 present and the AIP says CDA |
   | 5 | CDA, 100, ARQC | same |
   | 6 | CDA, 0, TC | same |
   | 7 | FDDA, 0, -, online | no signature in the probe GPO and the PDOL asks for `9F37` |
   | 8 | FDDA, 100, -, online | same |
   | 9 | DDA, 0 | the AIP doesn't say DDA (tried anyway) |
   | 10 | CDA, 0, ARQC | CDOL1 present but the AIP doesn't say CDA |

   Amounts are minor units of INR (100 = 1.00).
8. **Attempt** (`EmvSession.attempt`): `SELECT` the AID again, `GPO` with the
   challenge as UN, then the method's command. Records stay cached for the
   same AID, so DDA and CDA attempts don't re-read them; fDDA re-reads the last
   record when the GPO answer lacks `9F69` or `9F4B`.
9. **Accept** the first result with `un_len` in 4..8, terminal data at most
   64 bytes, and an SDAD that verifies with the certified key
   (`Rsa.verifySdad`). The attempt line records "signature OK (format XX, N
   bytes of terminal data)" and the session notes "signs with <label>: this
   card can be a Tapvault signer".
10. **Result.** `Enrolment(key, proof, report, recipe)`: the
    `SignerKey.EmvCard` with its layout, the `CardSig` (SDAD + terminal data)
    over the challenge, the `CardReport`, and the `CardRecipe` that worked.
    If no plan works: `CardCheckFailed("this card won't sign a challenge over
    NFC")`, verdict "it wouldn't sign a challenge with any method".

A failure in one application moves on to the next one (up to five); the
report keeps each earlier application's reason as a note. Every GPO starts
a new transaction on the card (its transaction counter `9F36` goes up),
and every signature is an RSA computation on the card, so an
enrolment that needs several plans takes a few seconds: the card must stay
still until the tap sheet closes.

## Later taps: `CardService.sign`

Every payment approval, card freeze and unfreeze approval calls
`CardService.sign(card, key, challenge, recipe)`:

- `key` is the enrolled card key rebuilt from the chain (`get_wallet`,
  `SignerInfo.cardKey()`), with its layout.
- `challenge` is the proposal's sealed `card_challenge` (from `get_proposal`,
  or from the last `list_proposals` when approving in Activity) or the freeze
  challenge `Domain.cardFreezeChallenge(account, freeze_nonce)`.
- `recipe` is `Store.cardRecipe`, decoded.

The certificate chain isn't checked at sign time (DDA and CDA attempts still
read the records, to find the DDOL or CDOL1; fDDA reads only the last one,
and only when the GPO answer lacks `9F69` or `9F4B`). The AID is the
recipe's; without a recipe the app selects the PPSE and uses its first
application (the first of `KNOWN_AIDS`, Visa, if there is no PPSE).

The saved recipe is tried first, then fallbacks that can give the same
layout (`CardService.fallbacks`), de-duplicated by (method, amount, CDA
variant, TTQ):

| Enrolled `terminal_data_len` | Fallbacks, in order |
|---|---|
| 4 (DDA with the default DDOL, CDA, fDDA version 00) | DDA; CDA ARQC amount 0; CDA ARQC amount 100; FDDA (online TTQ) |
| anything else (fDDA 12 or 19 bytes, DDA with a longer DDOL) | FDDA (online TTQ); FDDA (offline allowed); FDDA amount 100; DDA |

A result is accepted only if the terminal data has exactly
`terminal_data_len` bytes, the bytes at `un_offset` equal the first `un_len`
bytes of the challenge, and the SDAD verifies with the enrolled key. Then:

| Outcome | Thrown | What the app does |
|---|---|---|
| A result passes | returns the `CardSig` | submits it |
| A signature in the enrolled layout (`terminal_data_len` bytes, the challenge at `un_offset`) didn't verify with the enrolled key | `WrongCardException` ("this isn't the card enrolled in the wallet") | tap sheet hint "That's not the card in this wallet. Tap the card you added.", tries again (3 tries in all); if all three fail and any was the wrong card, the error is "That's not the card in this wallet. Use the card you added during setup." |
| Signatures came back, but none in the enrolled layout | `EmvException("the card signed, but not the way it did when it was added; tap it again")` | error shown, no retry; for Send, with the same addition as the next row |
| No signature at all | `EmvException("the card didn't sign (<last problem>)")` | error shown, no retry; for Send, the error adds "Payment #<id> is saved and waits 10 minutes for your card: ..." (the card can still approve it until 600 s after its challenge was sealed) |

```
Phone (AppViewModel)               NEAR (wallet contract)                    Card
  propose (phone signature) ------->  store action, schedule seal_challenge
                                      next block: seed sealed
  get_proposal (every 0.7 s) <------  card_challenge (32 bytes)
  SELECT <recipe AID> ------------------------------------------------------->
  GPO, UN = card_challenge[0..un_len) --------------------------------------->
  [READ RECORD(s)], then INTERNAL AUTHENTICATE / GENERATE AC (DDA / CDA) ---->
                                        <------------------------------------- SDAD (+ 9F69 for fDDA)
  (phone) check the layout, verify the SDAD with the enrolled key
  approve_card (+ execute) -------->  verify_card_challenge, policy
```

## APDUs the app sends

| Command | Bytes | Trace label | Code |
|---|---|---|---|
| SELECT PPSE | `00 A4 04 00 0E 32 50 41 59 2E 53 59 53 2E 44 44 46 30 31 00` | `SELECT PPSE` | `applications` |
| SELECT application | `00 A4 04 00 Lc <AID> 00` | `SELECT <AID>` | `select` |
| GET PROCESSING OPTIONS | `80 A8 00 00 Lc 83 <len> <PDOL values> 00` | `GPO` | `gpo` |
| READ RECORD | `00 B2 <record> <SFI*8+4> 00` | `READ RECORD <sfi>.<record>`, or `READ RECORD <sfi>.<record> (fresh)` for the fDDA re-read | `readRecords`, `lastRecord` |
| INTERNAL AUTHENTICATE | `00 88 00 00 Lc <DDOL values> 00` | `INTERNAL AUTHENTICATE` | `dda` |
| GENERATE AC | `80 AE 90 00 Lc <CDOL1 values> 00` (ARQC + CDA) or `80 AE 50 00 ...` (TC + CDA) | `GENERATE AC (ARQC, CDA)` / `GENERATE AC (TC, CDA)` | `cda` |
| GET RESPONSE | `00 C0 00 00 <SW2>`, repeated while SW1 is `61` | merged into the command's line | `send` |
| Resend | the same command with Le = SW2, once, when SW1 is `6C` | merged into the command's line | `send` |

Commands built by `EmvSession.apdu` end with Le `00`. The ISO-DEP timeout is
5 s per command (`IsoDepTransceiver`). A non-`9000` final status throws
`StatusWordException`: "card answered <command> with XXXX (<meaning>)".

## Terminal values (`EmvSession.terminalValue`)

What the app answers for each tag a DOL asks for. A value longer than the
requested length is cut to its first bytes; a shorter one is left-padded with
zeros.

| Tag | Name | Value | Meaning |
|---|---|---|---|
| `9F37` | Unpredictable Number | first `len` bytes of the challenge | the challenge |
| `9F66` | TTQ | `37804000` or `37004000` | online cryptogram required, or offline allowed (see fDDA) |
| `9F02` | Amount, Authorised | 6-byte BCD of the plan's amount | 0, or 100 = 1.00 |
| `9F03` | Amount, Other | 6 zero bytes | |
| `9F1A` | Terminal Country Code | `0356` | India |
| `5F2A` | Transaction Currency Code | `0356` | INR |
| `5F36` | Transaction Currency Exponent | `02` | |
| `9A` | Transaction Date | YYMMDD, phone's local date | |
| `9F21` | Transaction Time | HHMMSS, phone's local time | |
| `9C` | Transaction Type | `00` | purchase |
| `9F35` | Terminal Type | `22` | merchant, attended, offline with online capability |
| `9F33` | Terminal Capabilities | `E068C8` | manual entry, magstripe, chip; online PIN, signature, no CVM; SDA, DDA, CDA |
| `9F34` | CVM Results | `1F0302` | no CVM required, successful |
| anything else | | zeros of the requested length | e.g. TVR `95`, `9F45`, `9F4C`, `9F7C` |

The app never asks for or sends a PIN.

## `CardRecipe`: how this card signs

A `CardRecipe` records how the card signed at enrolment, so every later tap
starts with the method that works and produces the layout stored on-chain.

| Field | Values |
|---|---|
| `aid` | the application, hex, e.g. `A0000000041010` |
| `method` | `DDA`, `FDDA` or `CDA` |
| `amount` | 0 or 100 (minor units) |
| `variant` | fDDA: `FDDA_FULL` 0, `FDDA_AMOUNT` 1, `FDDA_UN` 2 (informational; `sign` builds all forms and picks by length). CDA: `CDA_ARQC` 0, `CDA_TC` 1. DDA: 0 |
| `ttq` | `TTQ_ONLINE` 0 (`37804000`) or `TTQ_OFFLINE_OK` 1 (`37004000`); only matters if the PDOL asks for `9F66` |

Encoding (`CardRecipe.encode`): `aid:METHOD:amount:variant:ttq`, for example:

```
A0000000031010:FDDA:0:0:0     Visa fDDA, 19-byte form, online TTQ
A0000000041010:CDA:100:0:0    Mastercard CDA, ARQC with amount 1.00
A0000000031010:DDA:0:0:0      classic DDA
```

`CardRecipe.decode` accepts 4 or 5 fields; a 4-field string (saved by builds
before the TTQ field existed, which always sent `37004000`) decodes with
`TTQ_OFFLINE_OK`. Anything unreadable decodes to null, and `sign` then relies
on the PPSE and the fallbacks.

Where it lives: `Store.cardRecipe` (SharedPreferences key `card_recipe`),
written in `AppViewModel.continueSetup` right after enrolment, read by
`AppViewModel.recipe()` before every signing tap (payments, card approvals,
freeze, unfreeze), and shown in Settings ("Bank card over NFC · <label>").
It is not on-chain and holds no card number.

## What the contract sees and checks

On-chain, a card signer is (JSON, `SignerKey::EmvCard`):

```json
{ "EmvCard": { "modulus": "<base64 ICC modulus>", "exponent": 3,
               "un_offset": 0, "un_len": 4, "terminal_data_len": 19 } }
```

and every card proof is a `CardSig`: `{ "sdad": "<base64>", "terminal_data": "<base64>" }`.

| Method | `terminal_data` | `un_offset` | `un_len` | `terminal_data_len` |
|---|---|---|---|---|
| DDA, default DDOL | UN | 0 | 4 | 4 |
| DDA, other DDOL | all DDOL values | position of `9F37` | its length | sum of DDOL lengths |
| fDDA, `FDDA_FULL` | UN + amount + currency + `9F69` | 0 | 4 | 19 with a 7-byte `9F69` |
| fDDA, `FDDA_AMOUNT` | UN + amount + currency | 0 | 4 | 12 |
| fDDA, `FDDA_UN` | UN | 0 | 4 | 4 |
| CDA | UN | 0 | length of `9F37` in CDOL1 (usually 4) | same |

`verify_card_challenge` (`crates/tapvault-core/src/emv.rs`) checks:

1. The key layout (`EmvKeyLayout::validate`, also at enrolment): modulus 64 to
   248 bytes (512 to 1984 bits), first byte non-zero, odd; exponent 3 or 65537;
   `un_len` 4 to 8; `terminal_data_len` at most 64; `un_offset + un_len` within
   it.
2. `terminal_data` is exactly `terminal_data_len` bytes.
3. `terminal_data[un_offset .. un_offset + un_len]` equals the first `un_len`
   bytes of the 32-byte challenge.
4. The SDAD recovers to `6A ... BC`, format `05` or `95`, hash algorithm `01`,
   a dynamic-data length that fits, `BB` padding, and the SHA-1 over
   `recovered[1 .. n-21) || terminal_data`. The format byte is inside the
   hash, so a `05` signature can't pass as `95` or the other way round.

Failures panic with "Card signature verification failed" (at
`setup_add_signer` a key that breaks the layout rules is refused before
that, with "Invalid signer key"). It does not check
the certificates (they contain the PAN and never leave the phone), the
cryptogram, or the amount, currency and `9F69` bytes in fDDA data (only the
UN carries the intent; the card's signature covers the rest).

The challenges the card signs (`Domain.kt`, mirrored in
`crates/tapvault-core/src/domain.rs`):

| Use | Challenge |
|---|---|
| Enrolment (setup) | `SHA-256("tapvault/near/v1/card-enroll" \|\| u32le(len) \|\| account \|\| u8(signer_count) \|\| key_hash)` |
| Approve a proposal | `SHA-256("tapvault/near/v1/card-approve" \|\| intent_hash \|\| seed)`; the seed is sealed a block after `propose`; valid for 600 s |
| Freeze | `SHA-256("tapvault/near/v1/card-freeze" \|\| u32le(len) \|\| account \|\| u64le(freeze_nonce))` |
| Card key hash | `SHA-256("tapvault/near/v1/key/emv" \|\| u32le(exponent) \|\| modulus)` |

Card proposals and card cancels exist in the contract but the app doesn't use
them. Only the first `un_len` bytes of the challenge reach the card.

**The app checks the same rules first.** Enrolment (and so the Card check)
refuses a card key that breaks the key rules of check 1 before any signing
attempt ("this card can't be a signer: ..."), and accepts a signature only
with `un_len` 4 to 8 and at most 64 bytes of terminal data. A card the
contract would refuse therefore fails the Card check, or fails setup before
anything is deployed, rather than at "Card added as a signer" with "adding
the card failed: Invalid signer key".

Gas grows with the card key: `approve_card` 2.7 Tgas for a 1024-bit key,
21.3 Tgas for 1984 bits with e = 65537; `setup_add_signer` 34.7 and 53.4 Tgas
(sandbox, 12 Nov). All far below the 300 Tgas limit.

## CA public keys (`capk.txt`)

### Format and loading

One key per line: `RID INDEX EXPONENT MODULUS`, all hex, separated by spaces.
Blank lines and lines whose first character is `#` are ignored. Every other
line must start with those four fields: an indented comment, a missing
field, or an index, exponent or modulus that isn't hex makes
`CapkStore.load()` throw, and then every card operation fails. It reads
the file from the `:core` resources each time the app builds a `CardService`
(`AppViewModel.cards()`, before every card operation);
`CapkStore.find(rid, index)` returns every key with that RID and index, and
the issuer certificate is tried against each. Tests add the simulated card's
CA with `CapkStore.plus(SimCard.TEST_CA)`; the app never does.

CA keys are used only on the phone, to check the certificate chain. The
contract never sees them, so adding a key needs no contract change and doesn't
affect existing wallets.

### What's in the file (12 Nov 2026)

| Network | RID | Indexes |
|---|---|---|
| Visa | `A000000003` | production 09 and 10 (active), 08, 07, 01 (expired); test 50, 92, 94, 95, 99 |
| Mastercard | `A000000004` | production 06 (active), 05 (1408-bit), 04, 03 (expired); test 00, 02, 05 (1024-bit), EF, F1, F3, F5, F6, F7, F8, F9, FA |
| RuPay | `A000000524` | 04, 05, 06 (eftlab.com list); 6A, 6B, 6C, 6D (bonum-capk-keys list, which doesn't label them; eftlab.com labels all four as test keys) |
| Discover / Diners Club International | `A000000152` | production 05 (Worldpay's production key list, expiry 31 Dec 2034; added 13 Nov) |

Expiry dates come from Visa's own CA key list for Visa (09 and 10 active
until 31 Dec 2036, 10 only for transit fare-gate terminals; 08 expired on
31 Dec 2024, 07 in 2017, 01 in 2009) and from eftlab.com for Mastercard (06
until 31 Dec 2028; 05 expired on 31 Dec 2024, 04 in 2017, 03 in 2009) and
RuPay (04, 05 and 06 listed as expiring 31 Dec 2024; eftlab.com's dates can
lag, it still shows 2028 for Visa 09). The app doesn't check CA key expiry,
and capk.txt has no expiry field. So a Visa card in use today should name
index 09 and a Mastercard 06, and whether current RuPay cards use any RuPay
key in the file is unknown.

Discover / Diners (`A000000152`) has only index 05, added for RuPay cards
co-badged with Diners Club International (their "INTERNATIONAL" application).
No other network has keys, so Amex (`A000000025`), JCB (`A000000065`) and
UnionPay (`A000000333`) cards fail with "no CA key for ...".

Sources named in the file: proxmark3 `client/resources/capk.txt` and
`github.com/Mungunkhuyag1/bonum-capk-keys`; Visa 10 from Visa's CA key list;
RuPay 04, 05, 06 from eftlab.com's list. NPCI doesn't publish RuPay keys, so a
newer RuPay index may be missing. Test keys are harmless here: a real card's
certificates don't verify against them, and the owner chooses which card to
enrol.

Every key, with its SHA-1 checksum computed from the file
(SHA-1 of RID, index, modulus, exponent as bytes). Compare with the network's
published checksum when in doubt:

| RID | Index | Bits | e | SHA-1 checksum | Note |
|---|---|---|---|---|---|
| A000000003 | 01 | 1024 | 03 | `D34A6A776011C7E7CE3AEC5F03AD2F8CFC5503CC` | production, expired 2009 |
| A000000003 | 07 | 1152 | 03 | `B4BC56CC4E88324932CBC643D6898F6FE593B172` | production, expired 2017 |
| A000000003 | 08 | 1408 | 03 | `20D213126955DE205ADC2FD2822BD22DE21CF9A8` | production, expired 31 Dec 2024 |
| A000000003 | 09 | 1984 | 03 | `1FF80A40173F52D7D27E0F26A146A1C8CCB29046` | production, active |
| A000000003 | 10 | 1536 | 03 | `BABB22F092C269235FFBFD5233853AFD769CB6FA` | production, active, transit fare gates only (added 12 Nov) |
| A000000003 | 50 | 1024 | 03 | `5765295089960938BAAA4431506E424295F98BD0` | test |
| A000000003 | 92 | 1408 | 03 | `429C954A3859CEF91295F663C963E582ED6EB253` | test |
| A000000003 | 94 | 1984 | 03 | `C4A3C43CCF87327D136B804160E47D43B60E6E0F` | test |
| A000000003 | 95 | 1152 | 03 | `EE1511CEC71020A9B90443B37B1D5F6E703030F6` | test |
| A000000003 | 99 | 1024 | 03 | `4ABFFD6B1C51212D05552E431C5B17007D2F5E6D` | test |
| A000000004 | 00 | 1280 | 03 | `EC0A59D35D19F031E9E8CBEC56DB80E22B1DE130` | test |
| A000000004 | 02 | 1536 | 03 | `33408B96C814742AD73536C72F0926E4471E8E47` | test (same modulus as F9) |
| A000000004 | 03 | 1024 | 03 | `5ADDF21D09278661141179CBEFF272EA384B13BB` | production, expired 2009 |
| A000000004 | 04 | 1152 | 03 | `381A035DA58B482EE2AF75F4C3F2CA469BA4AA6C` | production, expired 2017 |
| A000000004 | 05 | 1408 | 03 | `EBFA0D5D06D8CE702DA3EAE890701D45E274C845` | production, expired 31 Dec 2024 |
| A000000004 | 05 | 1024 | 03 | `53D04903B496F59544A84309AF169251F2896874` | test (same modulus as F8) |
| A000000004 | 06 | 1984 | 03 | `F910A1504D5FFB793D94F3B500765E1ABCAD72D9` | production, active |
| A000000004 | EF | 1984 | 03 | `21766EBB0EE122AFB65D7845B73DB46BAB65427A` | test |
| A000000004 | F1 | 1408 | 03 | `D8E68DA167AB5A85D8C3D55ECB9B0517A1A5B4BB` | test |
| A000000004 | F3 | 1152 | 03 | `A69AC7603DAF566E972DEDC2CB433E07E8B01A9A` | test |
| A000000004 | F5 | 1984 | 010001 | `C2239804C8098170BE52D6D5D4159E81CE8466BF` | test |
| A000000004 | F6 | 1792 | 03 | `502909ED545E3C8DBD00EA582D0617FEE9F6F684` | test |
| A000000004 | F7 | 1024 | 010001 | `EEB0DD9B2477BEE3209A914CDBA94C1C4A9BDED9` | test |
| A000000004 | F8 | 1024 | 03 | `F06ECC6D2AAEBF259B7E755A38D9A9B24E2FF3DD` | test |
| A000000004 | F9 | 1536 | 03 | `336712DCC28554809C6AA9B02358DE6F755164DB` | test |
| A000000004 | FA | 1152 | 03 | `5BED4068D96EA16D2D77E03D6036FC7A160EA99C` | test |
| A000000524 | 04 | 1408 | 03 | `6F843CE765B9144CE1A6BFEA46BC37B65081CE7F` | eftlab.com list, expiry 31 Dec 2024 there (added 12 Nov) |
| A000000524 | 05 | 1984 | 03 | `7081DF2A0C36360F24C122C574F0AD2E57893DD2` | eftlab.com list, expiry 31 Dec 2024 there (added 12 Nov) |
| A000000524 | 06 | 1984 | 03 | `E98F4134E1949A9A054E4679AC9A7EC83969E209` | eftlab.com list, expiry 31 Dec 2024 there (added 12 Nov) |
| A000000524 | 6A | 1024 | 03 | `51ED4570323CD41A0348BDFEA81CCC0B8D9BAB3F` | bonum list; test per eftlab.com |
| A000000524 | 6B | 1152 | 03 | `9602428A46271C63CCC6DD99477CDB70435D6D5B` | bonum list; test per eftlab.com |
| A000000524 | 6C | 1408 | 03 | `3B18A21BF34F781208145D7567982513D1CE8C92` | bonum list; test per eftlab.com (added 12 Nov) |
| A000000524 | 6D | 1984 | 03 | `411008F9921B89C62E2160F6D0358614115ECD4A` | bonum list; test per eftlab.com (added 12 Nov) |
| A000000152 | 05 | 1984 | 03 | `12BCD407B6E627A750FDF629EE8C2C9CC7BA636A` | production, expiry 31 Dec 2034; matches Worldpay's published hash (added 13 Nov) |

### Adding a CA key

When a report says "no CA key for <RID> index <XX>":

1. **Identify the key.** The RID is the first 10 hex digits of the AID; the
   index is the report's "CA index". The size of the missing key is in the
   trace: the issuer certificate is exactly as long as the CA modulus, so
   `90(248)` means a 1984-bit key, `90(176)` 1408-bit, `90(144)` 1152-bit.
2. **Find it** in an authoritative list: the network's CA key list (given to
   acquirers and terminal makers), or a public copy such as the proxmark3
   file or eftlab.com's list. Take the RID, index, exponent, modulus and the
   published SHA-1 checksum.
3. **Verify the checksum** = SHA-1 over RID || index || modulus || exponent,
   all as bytes:

   ```bash
   python3 -c 'import hashlib,sys; r,i,e,m=sys.argv[1:]; print(hashlib.sha1(bytes.fromhex(r+i+m+e)).hexdigest().upper())' \
     A000000524 05 03 C04E8018...   # RID INDEX EXPONENT MODULUS (full modulus)
   ```

   To print sizes and checksums for the whole file (from the repository
   root):

   ```bash
   python3 - android/core/src/main/resources/capk.txt <<'EOF'
   import hashlib, sys
   for line in open(sys.argv[1]):
       f = line.split()
       if len(f) != 4 or line.startswith('#'):
           continue
       rid, idx, exp, mod = f
       print(rid, idx, len(mod) * 4, hashlib.sha1(bytes.fromhex(rid + idx + mod + exp)).hexdigest().upper())
   EOF
   ```

   Don't add a key whose checksum you can't match against a published one.
4. **Add the line** `RID INDEX EXPONENT MODULUS` to `capk.txt`, with a `#`
   comment saying where it came from. Consider adding it to
   `EmvTest.capkFileLoadsWithChecksummedKeys`.
5. **Test and ship:** `cd android && ./gradlew :core:test`, rebuild the APK
   (`./build-apk.sh`), `adb install -r` it (see [ANDROID.md](ANDROID.md)), and
   run the Card check again with that card.

## Privacy

- **The PAN never leaves the phone.** `EmvSession` reads the PAN (`5A`)
  because the certificates contain it and it must match them. It stays inside
  that object. `CardReport` carries only the last four digits (`panLast4`);
  `PendingCard`, `CardRecipe`, the in-app log and the chain never contain it.
- **No certificates go on-chain.** Anyone can recover them with the public CA
  key, and the ICC certificate contains the PAN. Only the ICC public key and
  its layout go on-chain.
- **The ICC public key is a stable identifier.** It isn't the card number, but
  anyone who reads the same card (a payment terminal, a skimmer) can recover
  the same key from its certificates and recognise the wallet on-chain.
- **No PIN, no payment, nothing sent to the bank.** Cryptograms produced
  during a tap are discarded. Online fDDA and CDA do make the card produce a
  real ARQC on every tap; that is a card-network and compliance question for a
  real product (offline DDA or TC avoids it where a card supports it). See
  [STATUS.md](STATUS.md).

**What the trace keeps.** `EmvSession.describe` prints a response's TLV
structure. Values are printed only for tags in `TRACE_VALUES`, none of which
identify the card or its holder:

| Tags printed with their value | |
|---|---|
| `4F` AID, `84` DF name, `87` priority, `9F38` PDOL, `8C` CDOL1, `8D` CDOL2, `9F49` DDOL, `9F4A` SDA tag list | how to talk to the card |
| `82` AIP, `94` AFL, `8E` CVM list, `9F07` usage control, `9F08` application version, `9F0D` `9F0E` `9F0F` issuer action codes | capabilities |
| `8F` CA index, `9F32` issuer exponent, `9F47` ICC exponent | key metadata |
| `9F26` cryptogram, `9F27` CID, `9F36` ATC, `9F10` issuer application data, `9F69` card authentication data, `9F6C` card qualifiers, `9F66`, `9F6E` | per-transaction data |
| `9F42` currency, `9F44` currency exponent, `5F28` issuer country, `5F2D` language | |

Also printed: the label `50` and preferred name `9F12` as text, and a format-1
answer (`80`) when it is at most 48 bytes (so a GPO's AIP + AFL or a GENERATE
AC's cryptogram, never an RSA signature).

**What the trace leaves out.** Every other tag is printed as `TAG(length)`
with no value: the PAN `5A`, track data `57`, `56`, `9F6B`, `9F1F`, `9F20`,
the cardholder name `5F20` / `9F0B`, expiry `5F24` and effective date `5F25`,
the PAN sequence number `5F34`, the certificates and remainders `90`, `92`,
`9F46`, `9F48`, the signature `9F4B`, and anything unknown. Non-TLV answers
appear as "(N bytes, not TLV)".

## Reading a Card check report

### Where it comes from

- **Card check:** Welcome → "Check my card first", or once a wallet exists
  tap the card on Home, or Settings → "Your card". Then the "Check a
  card" button and the tap sheet "Hold the card you want to check". Nothing
  touches the chain and nothing is saved.
- **Setup:** the same report appears on the Setup screen after the card step,
  whether it worked or not, with a "Share card report" button. Starting a
  setup clears any earlier report. Run the Card check with every card before
  creating a wallet.

### On screen (`CardReportCard`)

| Row | Source | Example |
|---|---|---|
| Title | network and last four digits (`????` if unknown) | "Visa •••• 1234" |
| Caption | application label, or the AID if none | "VISA DEBIT" |
| Badge | "Can sign" if a method worked, else "Can't sign" | |
| Offline auth | AIP flags | "SDA · DDA", or "none" |
| CA key index | `8F` | "09" |
| Issuer key | recovered issuer key size | "1408 bit" |
| Card key | ICC key size and exponent | "1024 bit, e=3" |
| Signs with | the method's label | "signature in GET PROCESSING OPTIONS (fDDA)" |
| Challenge format | the DDOL (only used by DDA) | "default 9F3704" |
| Notes | one line per note, prefixed "·" | |

A value the check didn't reach shows "–".

"Share report" (Card check) and "Share card report" (Setup) send
`CardReport.diagnostics()` as plain text through Android's share sheet
("Share card report"); you choose where it goes.

### The shared text, line by line

```
network: <Visa|Mastercard|RuPay|unknown> (<AID>, "<label>")
AIP: <hex>  SDA=<bool> DDA=<bool> CDA=<bool>
CA index: <8F or ->  issuer key: <bits or -> bit
card key: <bits or -> bit, e=<exponent or ->  DDOL: <9F49 hex or "default 9F3704">
card: ****<last 4 or ????>
signs with: <method label, or "nothing that worked">
- <note>                      one per note; the last explains a failure
attempts:                     only if a signing plan ran
  <METHOD> <AID>[ amount 100][ TC][ offline-ok]: <result>
trace (card number, name, track data and certificates left out):
  <command> -> <status word> <TLV summary>
```

Notes you may see: "no PPSE (...); trying known applications" or
"unreadable PPSE (...); trying known applications", "<AID>: <error>" or
"<AID>: unreadable answer (...)" for an application whose SELECT, GPO or
READ RECORD failed, or, when another application was tried after it, whose
certificate chain, key check or signing failed, "issuer certificate expired
MM/YY", "signs with <label>: this card can be a Tapvault signer", and finally
"can't be a Tapvault signer: <reason>" when it can't.

Attempt lines use the `SignMethod` name (`DDA`, `FDDA`, `CDA`). " amount 100"
appears for amount 1.00, " TC" for the CDA TC variant, " offline-ok" for the
offline-allowed TTQ on a card whose PDOL asks for `9F66`.

Trace lines: `TAG=HEX` is a printed value, `TAG(n)` a value of n bytes left
out, `TAG="text"` a label, `TAG[...]` a template with its contents. A failed
command shows only its status, e.g. `SELECT PPSE -> 6A82`.

### Examples

The layouts below are exactly what the code prints (`CardReport.text`,
`CardReport.diagnostics`, `EmvSession.describe`). **The values are invented
for illustration**: no real card has produced these.

**Visa, signs with fDDA (expected for Visa payWave).**

```
network: Visa (A0000000031010, "VISA DEBIT")
AIP: 2000  SDA=false DDA=true CDA=false
CA index: 09  issuer key: 1408 bit
card key: 1024 bit, e=3  DDOL: default 9F3704
card: ****1234
signs with: signature in GET PROCESSING OPTIONS (fDDA)
- signs with signature in GET PROCESSING OPTIONS (fDDA): this card can be a Tapvault signer
attempts:
  FDDA A0000000031010: signature OK (format 95, 19 bytes of terminal data)
trace (card number, name, track data and certificates left out):
  SELECT PPSE -> 9000 6F[84=325041592E5359532E4444463031 A5[BF0C[61[4F=A0000000031010 50="VISA DEBIT" 87=01]]]]
  SELECT A0000000031010 -> 9000 6F[84=A0000000031010 A5[50="VISA DEBIT" 87=01 9F38=9F66049F02069F03069F1A0295055F2A029A039C019F3704]]
  GPO -> 9000 77[82=2000 94=1001010018010201 57(19) 5F34(1) 9F10=06011203A00000 9F26=4F1D0C9A33E2B701 9F27=80 9F36=0041 9F4B(128) 9F6C=1600]
  READ RECORD 2.1 -> 9000 70[5F20(26) 9F1F(18)]
  READ RECORD 3.1 -> 9000 70[8F=09 90(248) 9F32=03 9F4A=82]
  READ RECORD 3.2 -> 9000 70[5A(8) 5F24(3) 9F46(176) 9F47=03 9F69=01C3D2A1F01600]
  SELECT A0000000031010 -> 9000 6F[84=A0000000031010 A5[50="VISA DEBIT" 87=01 9F38=9F66049F02069F03069F1A0295055F2A029A039C019F3704]]
  GPO -> 9000 77[82=2000 94=1001010018010201 57(19) 5F34(1) 9F10=06011203A00000 9F26=9B07E21C4D5A6F30 9F27=80 9F36=0042 9F4B(128) 9F6C=1600]
  READ RECORD 3.2 (fresh) -> 9000 70[5A(8) 5F24(3) 9F46(176) 9F47=03 9F69=01A9B8C7D61600]
```

Reading it: the probe GPO already returned `9F4B`, so plan 1 (fDDA, online
TTQ) ran first; `9F27=80` is an ARQC; the fresh read of record 3.2 gave this
transaction's `9F69`; the full 19-byte form verified with format `95`. The
wallet stores `terminal_data_len` 19.

**Mastercard, signs with CDA after a declined zero amount (expected for
Mastercard).**

```
network: Mastercard (A0000000041010, "Mastercard")
AIP: 1980  SDA=false DDA=false CDA=true
CA index: 05  issuer key: 1152 bit
card key: 1024 bit, e=3  DDOL: default 9F3704
card: ****4417
signs with: signature in GENERATE AC (CDA)
- signs with signature in GENERATE AC (CDA): this card can be a Tapvault signer
attempts:
  CDA A0000000041010: GENERATE AC answered without a signature (declined: AAC)
  CDA A0000000041010 amount 100: signature OK (format 05, 4 bytes of terminal data)
trace (card number, name, track data and certificates left out):
  SELECT PPSE -> 9000 6F[84=325041592E5359532E4444463031 A5[BF0C[61[4F=A0000000041010 50="Mastercard" 87=01]]]]
  SELECT A0000000041010 -> 9000 6F[84=A0000000041010 A5[50="Mastercard" 87=01]]
  GPO -> 9000 77[82=1980 94=0801010010010301]
  READ RECORD 1.1 -> 9000 70[57(19) 5F20(2) 9F1F(24)]
  READ RECORD 2.1 -> 9000 70[5A(8) 5F24(3) 5F28=0356 5F34(1) 8C=9F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F34039F21039F7C14 9F4A=82]
  READ RECORD 2.2 -> 9000 70[8F=05 90(176) 9F32=03 92(4)]
  READ RECORD 2.3 -> 9000 70[9F46(144) 9F47=03 9F48(26)]
  SELECT A0000000041010 -> 9000 6F[84=A0000000041010 A5[50="Mastercard" 87=01]]
  GPO -> 9000 77[82=1980 94=0801010010010301]
  GENERATE AC (ARQC, CDA) -> 9000 77[9F27=00 9F36=0031 9F26=8E44C1D20A7F3B65 9F10=0110A00000000000]
  SELECT A0000000041010 -> 9000 6F[84=A0000000041010 A5[50="Mastercard" 87=01]]
  GPO -> 9000 77[82=1980 94=0801010010010301]
  GENERATE AC (ARQC, CDA) -> 9000 77[9F27=80 9F36=0032 9F4B(128) 9F10=0110A00000000000]
```

Reading it: no PDOL and no signature in the GPO, AIP says CDA, CDOL1 present,
so the CDA plans ran; the zero amount was declined (`9F27=00`, AAC, no
`9F4B`); amount 1.00 gave an ARQC with a signature. Recipe
`A0000000041010:CDA:100:0:0`, `terminal_data_len` 4.

**RuPay, CA key unknown.**

```
network: RuPay (A0000005241010, "RuPay")
AIP: 3900  SDA=false DDA=true CDA=true
CA index: 6E  issuer key: - bit
card key: - bit, e=-  DDOL: default 9F3704
card: ****0007
signs with: nothing that worked
- can't be a Tapvault signer: no CA key for A000000524 index 6E: this app doesn't know that network key yet; share the card report so it can be added
trace (card number, name, track data and certificates left out):
  ...
  READ RECORD 2.2 -> 9000 70[8F=6E 90(248) 9F32=03]
  ...
```

No `attempts:` section: the certificate chain failed before any signing plan
ran. The key needed is RuPay index 6E, 1984 bits (`90(248)`). Add it as in
[Adding a CA key](#adding-a-ca-key) and check again. (Index 6E is an invented
example.) During setup the same failure reads "This card can't sign for
Tapvault: no CA key for A000000524 index 6E: ... Tap Continue setup to try
another contactless card, or Start over."

**A card that answers but won't sign.**

```
network: Visa (A0000000031010, "VISA DEBIT")
AIP: 2000  SDA=false DDA=true CDA=false
CA index: 09  issuer key: 1408 bit
card key: 1024 bit, e=3  DDOL: default 9F3704
card: ****5521
signs with: nothing that worked
- can't be a Tapvault signer: it wouldn't sign a challenge with any method
attempts:
  FDDA A0000000031010 offline-ok: no signature in the GPO answer (online: ARQC)
  DDA A0000000031010: card answered INTERNAL AUTHENTICATE with 6D00 (command not supported)
  FDDA A0000000031010: no signature in the GPO answer (online: ARQC)
  FDDA A0000000031010 amount 100: no signature in the GPO answer (online: ARQC)
trace (card number, name, track data and certificates left out):
  ...
```

The chain verified (card key known), but the card never signed: no `9F4B` in
any GPO answer, and INTERNAL AUTHENTICATE isn't supported. With this build
this card can't be a signer. The report is still valuable: it shows exactly
which variants were tried.

**Contactless off, or not a payment card.**

```
network: unknown (, "")
AIP: 0000  SDA=false DDA=false CDA=false
CA index: -  issuer key: - bit
card key: - bit, e=-  DDOL: default 9F3704
card: ****????
signs with: nothing that worked
- no PPSE (card answered SELECT PPSE with 6A82 (not found)); trying known applications
- A0000000031010: card answered SELECT A0000000031010 with 6A82 (not found)
- A0000000041010: card answered SELECT A0000000041010 with 6A82 (not found)
- A0000005241010: card answered SELECT A0000005241010 with 6A82 (not found)
- A0000000043060: card answered SELECT A0000000043060 with 6A82 (not found)
- A0000000032010: card answered SELECT A0000000032010 with 6A82 (not found)
- can't be a Tapvault signer: card answered SELECT A0000000032010 with 6A82 (not found)
trace (card number, name, track data and certificates left out):
  SELECT PPSE -> 6A82
  SELECT A0000000031010 -> 6A82
  SELECT A0000000041010 -> 6A82
  SELECT A0000005241010 -> 6A82
  SELECT A0000000043060 -> 6A82
  SELECT A0000000032010 -> 6A82
```

No Visa, Mastercard or RuPay application answered. Either it isn't a
payment card (or it is one from another network), or its contactless
interface isn't active. How a disabled contactless interface answers isn't
known for these cards (nothing has been tested): it may refuse SELECT with an
error status like the one above. Check the bank app's contactless setting,
and whether the card works at a shop's contactless terminal. (On screen the
card picture then shows "TAPVAULT" instead of the last four digits.)

### What each failure means

The last note ("can't be a Tapvault signer: ...") or the setup error gives the
reason. Errors before any card answer (not ISO-DEP, NFC off, no tap) produce
no report; see [ANDROID.md](ANDROID.md#nfc-and-card-taps).

| Reason | Meaning | What to do |
|---|---|---|
| "This card doesn't speak ISO-DEP. Transit cards, stickers and key fobs can't sign; use a contactless bank card." (an error, no report) | Not an ISO 14443-4 card | Use a contactless bank card |
| "card answered SELECT ... with 6A82 (not found)" for every application | See the example above | Check the bank app's contactless setting; try a shop terminal; other networks aren't supported |
| "card answered GPO with XXXX (...)" | The card refused the terminal values the app sent | Share the report; the PDOL (`9F38`) in the trace shows what it asked for |
| "card answered READ RECORD s.r with 6A83 (record not found)" | The AFL named a record the card won't return | Share the report |
| "GPO response too short", "GPO response has no AIP", "short response to ..." | Malformed or cut-off answer | Check again holding the card still; share the report if it repeats |
| "the card's answer couldn't be read" (note "<AID>: unreadable answer (...)") | TLV that doesn't parse, or a truncated PDOL ("truncated DOL tag", "truncated DOL length") | Share the report |
| "card has no CA key index (no offline data authentication)" | No ODA over contactless: online-only card | Can't be a signer |
| "no CA key for <RID> index <XX>: this app doesn't know that network key yet; share the card report so it can be added" | `capk.txt` lacks that key | [Add the key](#adding-a-ca-key) |
| "issuer certificate did not verify against CA <XX>" | The key at that index isn't the one that signed the certificate (for example only a test key with that index is known), or a bad read | Check the key against a published list; check again |
| "no issuer certificate", "no issuer exponent" | Records lack the issuer certificate data | Share the report |
| "no PAN record" | No tag `5A` in the records (the app doesn't take the PAN from `57`) | Share the report; needs a code change |
| "no card certificate (the card has no key of its own)", "no card exponent" | SDA-only or no ICC key | Can't be a signer |
| "card certificate format", "card key length mismatch", "card certificate is for a different card number", "signature length N != modulus M", "signature not reduced", "certificate check failed" | The ICC certificate didn't decode as expected | Share the report |
| "card certificate hash mismatch (static data)" | The static data the app hashed differs from what the issuer signed: an SDA tag list other than `82` (only `82` is handled), a record in SFI 1 to 10 that isn't a `70` template (hashed whole), or a bad read | Check again; if it repeats, share the report (likely a code fix in `readRecords`) |
| "card key exponent too large" | ICC exponent over 31 bits; not seen in EMV | Share the report |
| "this card can't be a signer: its key is N bits; the wallet accepts 512 to 1984", "... its key isn't a valid RSA modulus", "... its key exponent is N; the wallet accepts 3 or 65537" (note "can't be a Tapvault signer: its key ...") | The card key breaks the contract's rules for a signer key (512 to 1984 bits, no leading zero byte, odd, exponent 3 or 65537); found before any signing attempt with that application | Can't be a signer |
| "it wouldn't sign a challenge with any method" | Every plan failed; read the attempts | Table below |

| Attempt result | Meaning |
|---|---|
| "signature OK (format XX, N bytes of terminal data)" | Success. N becomes `terminal_data_len` on-chain |
| "no signature in the GPO answer (online: ARQC)" | fDDA plan: the card went online without signing (no `9F4B` in the GPO answer or the fresh record) |
| "no signature in the GPO answer (approved offline: TC)" / "(declined: AAC)" / "(no cryptogram)" | fDDA plan: no `9F4B`, with that cryptogram type |
| "GENERATE AC answered without a signature (declined: AAC)" | CDA plan: declined, typically for amount 0; the amount-100 plan follows |
| "GENERATE AC answered without a signature (online: ARQC)" | The card didn't do CDA on an ARQC; the TC plan follows if the AIP says CDA |
| "card answered INTERNAL AUTHENTICATE with 6D00 (command not supported)" | No DDA over contactless (common) |
| "card answered ... with 6985 (conditions of use not satisfied)" | The card refused the command in this state |
| "signature didn't verify with the card's certified key" | The card signed, but over data the app didn't reproduce (another fDDA form, extra data), or the layout is outside what the contract accepts (a UN shorter than 4 or longer than 8 bytes, more than 64 bytes of terminal data). Needs code work: share the report |
| "the card's PDOL has no Unpredictable Number", "the card's DDOL has no Unpredictable Number", "no CDOL1, so no GENERATE AC", "CDOL1 has no Unpredictable Number" | That method is impossible on this card |
| "INTERNAL AUTHENTICATE returned no signature" | DDA answered `9000` without `80` or `9F4B` |
| "unreadable answer (...)" | TLV that doesn't parse, or a truncated DDOL or CDOL1 ("truncated DOL tag", "truncated DOL length") |

At payment time (`CardService.sign`) there is no report; the tap sheet and
the error say what happened: "That's not the card in this wallet. Tap the card
you added." (a signature in the enrolled layout came back but didn't verify;
after three tries, "That's not the card in this wallet. Use the card you added
during setup."), "the card signed, but not the way it did when it was added;
tap it again" (signatures came back, none in the enrolled layout), "the card
didn't sign (<last problem>)", or, after three failed tries with no wrong
card among them, "Couldn't read the card. Hold it flat against the back of
the phone, near the camera, for 3 seconds."
Details: [ANDROID.md](ANDROID.md#nfc-and-card-taps),
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Expected behaviour per network

Confidence is about this code working with that network's cards. It rests on
the EMV specifications, public captures (proxmark3 traces, Visa's documents)
and the simulated card, not on a real tap.

| Network | Usual AID | Expected method | Expected recipe | Terminal data | Confidence |
|---|---|---|---|---|---|
| Mastercard (credit, debit) | `A0000000041010`; Maestro `A0000000043060` | CDA via GENERATE AC | `A0000000041010:CDA:100:0:0` (zero amount declined first), or `...:CDA:0:0:0` | UN, 4 bytes, format `05` | Medium |
| Visa (payWave) | `A0000000031010`; Visa Electron `A0000000032010` | fDDA in the GPO answer | `A0000000031010:FDDA:0:0:0` | 19 bytes (UN, amount, currency, `9F69`), format `95` | Medium |
| RuPay | `A0000005241010` | Unknown | Unknown | Unknown | Low |
| Any network, cards that answer INTERNAL AUTHENTICATE | | DDA | `<AID>:DDA:0:0:0` | UN, 4 bytes, format `05` | Medium for those cards; many contactless cards don't answer it |
| Discover / Diners (RuPay co-badged "INTERNATIONAL") | `A0000001523010` | fDDA in the GPO answer (a real card returned `9F4B` there) | `A0000001523010:FDDA:0:0:0` | Unknown; the app tries its three fDDA forms | Low |
| Amex, JCB, UnionPay | | | | | Not supported: no CA keys |

**Mastercard.** The AIP should have the CDA bit and CDOL1 should be in the
records. Plan 4 (ARQC, amount 0) may come back as an AAC with no signature;
plan 5 (amount 1.00) should sign. Risks: the card wants terminal values the app
fills with zeros (TVR `95`, `9F45`, `9F4C`, `9F7C`), refuses CDA on an ARQC
(plan 6 then asks for a TC), or wants records read before GENERATE AC on the
second transaction of the session (attempts don't re-read them).

**Visa.** The PDOL should ask for `9F66` and `9F37`; the probe GPO should
already carry `9F4B` because the TTQ allows offline data authentication for
online authorisations. `9F69` is expected in the last AFL record. Risks: the
card signs only offline (plan 2 covers it, format `05`), the fDDA data has a
form other than the three the app builds ("signature didn't verify with the
card's certified key"), or `9F69` can't be re-read.

**RuPay.** NPCI's contactless specifications aren't public. The app tries
whatever the card's AIP, PDOL and CDOL1 allow. The most likely early failure is
an unknown CA index (the file's RuPay keys are either listed as expiring 31
Dec 2024 or labelled test keys; see [CA public keys](#ca-public-keys-capktxt)):
the Card check then names it. National Common Mobility
Cards (RuPay) are RuPay cards for this purpose: unknown.

**Several applications.** Co-badged or multi-application cards are tried in
PPSE priority order, up to five applications; the first that signs is used.

**Phones and watches acting as cards** (tokenised wallets) are untested; they
often have no offline data authentication, which Tapvault needs.

## When a card fails: what to collect

1. The **shared report** from the Card check ("Share report") or the Setup
   screen ("Share card report"): the whole text, including the trace. It
   holds the last four digits, no other card data.
2. The **exact error** shown in the app (a screenshot is fine), and the
   lines from Settings → Advanced → Technical log around it.
3. **The card:** network, debit / credit / prepaid, issuing bank, whether it
   has the contactless symbol, whether contactless is enabled in the bank's
   app, and whether it works at a shop's contactless terminal.
4. **The phone:** model, Android version, where the NFC antenna is (where the
   tap worked), whether the tap sheet reached "Keep holding the card…".
5. **The build:** the commit the APK was built from.

Never share the full card number, a photo of the card, the expiry date or the
CVV. None of them is needed.

## Known gaps in the card code

- **Real cards are untested** (all of the above).
- **Static data:** only an SDA tag list of `82` is handled; a record from SFI
  1 to 10 that isn't a `70` template is hashed whole instead of being refused.
- **PAN source:** only `5A`; a card that carries its PAN only in track data
  (`57`) fails with "no PAN record".
- **Certificate expiry and revocation:** the ICC certificate's expiry isn't
  checked, an expired issuer certificate is only noted, and there is no CA
  revocation list.
- **Zeros for unknown terminal values** (`95`, `9F45`, `9F4C`, `9F7C` and
  others) may not satisfy every card.
- **Cryptograms on every tap:** online fDDA and CDA make the card produce an
  ARQC that is never sent to the bank (compliance question, not a contract
  issue).

## Related docs

- [OVERVIEW.md](OVERVIEW.md): what Tapvault is, glossary of EMV and NEAR terms.
- [ARCHITECTURE.md](ARCHITECTURE.md): components and flows end to end.
- [ANDROID.md](ANDROID.md): NFC reader mode, taps and retries, the app's storage.
- [CONTRACT.md](CONTRACT.md): `setup_add_signer`, `approve_card`, `freeze` and the rest.
- [DESIGN.md](DESIGN.md): why a card can be a signer, skimming, the commit-then-challenge rule.
- [TESTING.md](TESTING.md): the real-phone, real-card test plan.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): every user-visible error.
- [STATUS.md](STATUS.md): what is verified and what isn't.
