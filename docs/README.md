# Tapvault documentation

Tapvault makes a contactless bank card and a phone key the two co-signers of a
NEAR smart-contract wallet, on NEAR testnet. Status on 12 Nov 2026: everything
passes with simulated cards; nothing has run against a real bank card or a
real phone's NFC yet ([STATUS.md](STATUS.md)).

## Every doc

| Doc | What's in it |
|---|---|
| [../README.md](../README.md) | Product summary, how to build and test, status at a glance, measured gas |
| [../AGENTS.md](../AGENTS.md) | For coding agents and new contributors: layout, exact commands, environment, invariants, how to change things safely, where each feature lives |
| [OVERVIEW.md](OVERVIEW.md) | What Tapvault is, who does what, the user journey from install to payment, the policy, the glossary (EMV, Android, NEAR, Tapvault terms), hackathon context |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components and boundaries, accounts and keys, sequence diagrams for every flow, where state lives, the NEAR client, error handling |
| [CONTRACT.md](CONTRACT.md) | Contract API reference: argument types, every method and its checks, views, events, errors, constants, signed messages, the shipped build, changing the contract safely |
| [HARDWARE-WALLET-PLAN.md](HARDWARE-WALLET-PLAN.md) | Plan, phase 1 built (13 Nov): hardware-wallet features for Tapvault, Security Center and screens, user flows (backup card, lost card, recover on a new phone, hold-to-sign), build order |
| [WALLET-FEATURES-PLAN.md](WALLET-FEATURES-PLAN.md) | Plan, step 0 built (14 Nov, mainnet still off): a Mainnet/Testnet switch in Settings, then tokens, send/receive tokens, swap and bridge via NEAR Intents 1Click, buy and sell for cash; all on the current contract, screens, flows, build order |
| [MANUAL-TESTS.md](MANUAL-TESTS.md) | What only a person with a phone and real cards can test, per plan phase, with what is already tested automatically |
| [DESIGN.md](DESIGN.md) | Security model and threat analysis: why the card challenge is sealed in a later block, limits and delays, freeze and cancel rules, known gaps |
| [CARDS.md](CARDS.md) | How the app talks to bank cards over NFC: EMV basics, the three signing methods (DDA, fDDA, CDA), enrolment and later taps command by command, CA keys, privacy, reading a Card check report, expected behaviour per network |
| [ANDROID.md](ANDROID.md) | The Android app's internals, build, install, configuration (sponsor), logs |
| [TESTING.md](TESTING.md) | What is tested how, the automated tests (including live testnet), the step-by-step real-phone test plan, an acceptance checklist and a test report template |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Every user-visible error, what it means, what to do |
| [STATUS.md](STATUS.md) | What is verified and what isn't, the 12 Nov review, known issues, next steps |
| [DEMO.md](DEMO.md) | The demo-video script |
| [../contract/res/README.md](../contract/res/README.md) | The contract build the app ships: hashes, how it was built, how to replace it |

## Reading paths

### I'm testing on a phone

1. [STATUS.md](STATUS.md): what is known to work and what is untested.
2. [OVERVIEW.md](OVERVIEW.md), "The user journey": what each screen does.
3. [ANDROID.md](ANDROID.md): build or get the APK, install it, phone settings.
4. [TESTING.md](TESTING.md): the real-phone plan, starting with Card check on every card.
5. [CARDS.md](CARDS.md): how to read a card report and what to send back.
6. [TROUBLESHOOTING.md](TROUBLESHOOTING.md): when a message appears.

Before you start: don't uninstall the app or clear its storage while a wallet
is set up (that deletes the phone key and strands the wallet's test NEAR), and
install updates built on the same computer as the installed APK.

### I'm changing the Android app

1. [../AGENTS.md](../AGENTS.md): commands, invariants, where things are.
2. [ARCHITECTURE.md](ARCHITECTURE.md): flows, state, the NEAR client, error handling.
3. [ANDROID.md](ANDROID.md): app internals and build.
4. [CARDS.md](CARDS.md): if you touch anything that talks to the card.
5. [TESTING.md](TESTING.md) and [STATUS.md](STATUS.md): what to re-test, known issues.

### I'm changing the contract

1. [../AGENTS.md](../AGENTS.md): invariants (byte vectors, the shipped wasm and its hash, no upgrade path).
2. [DESIGN.md](DESIGN.md): the security model the contract implements.
3. [CONTRACT.md](CONTRACT.md): the API you are changing.
4. [ARCHITECTURE.md](ARCHITECTURE.md): how the app calls it.
5. [../contract/res/README.md](../contract/res/README.md): shipping a new build.
6. [TESTING.md](TESTING.md): flows, vectors, sandbox and live tests.

### I need the security model

1. [DESIGN.md](DESIGN.md): threats, the sealed card challenge, limits and delays, what isn't protected.
2. [OVERVIEW.md](OVERVIEW.md), "What the policy enforces": every rule in one table.
3. [STATUS.md](STATUS.md): the 12 Nov review and the contract security re-check.
4. [CONTRACT.md](CONTRACT.md): exactly which check each method makes.
5. [ARCHITECTURE.md](ARCHITECTURE.md), "Trust boundaries" and "Accounts and keys".

### I'm new and want the big picture

[../README.md](../README.md), then [OVERVIEW.md](OVERVIEW.md), then
[ARCHITECTURE.md](ARCHITECTURE.md).

## Conventions in these docs

- Quoted UI text is exactly what the app shows (from `Screens.kt`,
  `Components.kt` and `AppViewModel.kt`; error texts also from `Keystore.kt`,
  `NearClient.kt`, `Emv.kt` and the contract's `error.rs`).
- Code is named by symbol (`AppViewModel.send`, `NearClient.rpc`) rather than
  by line number.
- Test status uses three labels (as in [TESTING.md](TESTING.md)): **tested**
  (run against the real thing with no stand-ins, for example the Kotlin and
  Rust byte vectors, or the RPC error handling against live testnet RPCs),
  **tested with the simulated card** (the code ran end to end, but the bank
  card was the test fixture `SimCard` or `TestCard` and the phone key a
  software key; this includes the live testnet runs; tests only, the app has
  no simulated card), and **untested** (nothing has exercised it yet, which
  includes every real card and every real phone).
- Secrets are never written down: `android/local.properties` holds keys, and
  only its property names (`sdk.dir`, `tapvault.sponsor.account`,
  `tapvault.sponsor.key`, and the non-secret switch `tapvault.mainnet`)
  appear in the docs.
