# Wallet features plan: tokens, swap, bridge, buy, sell (mainnet)

Status: **plan, 14 Nov 2026. Step 0 (the network switch) is built, with
mainnet off in every build. Step 1 (tokens) is built without prices; steps
2–5 are not.** Step 1 as built: `Tokens.kt` (core: built-in list per network,
`TokenPrefs` added/hidden per wallet, `ft_metadata`, `ft_balance_of`,
`storage_balance_of`, `storage_deposit` paid by the fee account, FastNEAR
discovery; `TokensTest`), `TokensUi.kt` (Home Tokens section, Manage tokens
sheet, token sheet with Enable). Tokens the index finds but Tapvault doesn't
list never show on Home until added (spam). No icons (contract-supplied
images) and no USD prices yet (1Click, mainnet only). See "Step 0: what was
built" at the end of section 0. Scope chosen on 14 Nov: the features below, **all on NEAR
mainnet**. They work with the contract every wallet already runs (v0.3), so
no contract change, no new wasm and no new signed bytes. They do need
mainnet in the app first: a Mainnet / Testnet switch in Settings, as in
MetaMask or Phantom (section 0), which changes invariant 7 ("Testnet only")
in [../AGENTS.md](../AGENTS.md).

| In scope (mainnet) | How |
|---|---|
| Token balances, prices, add and manage tokens | App only: FastNEAR token list, `ft_metadata` over RPC, prices from 1Click `/v0/tokens` |
| Send and receive tokens | `Transfer { asset: Ft { token } }` already exists in the contract; the fee account pays `storage_deposit` for the wallet before a token can arrive |
| Swap | NEAR Intents 1Click: quote, deposit address, a normal `Transfer` to it, `/deposit/submit`, poll `/status` |
| Bridge to and from other chains | The same 1Click flow with another chain on one side |
| Buy with cash | An on-ramp provider (Transak / MoonPay / Onramper) opened with the wallet's account; the NEAR or token simply arrives |
| Sell for cash | The same provider's off-ramp gives a deposit address; then it's a normal Send |

Out of scope for now (each needs contract v0.4 or isn't worth it): native
staking, NFT send, wrap/unwrap NEAR, lending, meme launchpads.

---

## 0. Mainnet and testnet in one app (comes first)

Testnet is hard-coded today. Like MetaMask and Phantom, the app gets a
network switch in Settings; each wallet belongs to one network for life.

### 0.1 One `Network` object instead of literals

Add `Network.kt` to `android/core` (`TESTNET`, `MAINNET`) and pass it to
`NearClient`, `Onboarding`, `SeedWallet` and the app. It owns:

| Field | Today (testnet literal) | Where |
|---|---|---|
| RPC list | `TESTNET_RPCS` (`rpc.testnet.fastnear.com`, `rpc.testnet.near.org`) | `NearClient.kt` |
| Explorer | `testnet.nearblocks.io` | `NearClient.explorerTx`, `explorerAccount` |
| Account suffix | `.testnet` | `Wallet.kt` (`Onboarding.createAccounts`), `AppViewModel` (recover-lookup check refuses anything not ending in `.testnet`) |
| Faucet | `createTestnetAccount` | `Wallet.kt`; mainnet has none |
| Display name | "NEAR testnet" | `Screens.kt` (Receive, Settings), `Security.kt`, `AppViewModel` messages, `NearClient.readableError`, `SeedWallet.preflight`, `Keystore.kt` errors |
| Placeholders | `bob.testnet`, `tv-1a2b3c4d5e.testnet` | `Screens.kt`, `Join.kt`, `KeyAndContacts.kt`, `SeedScreens.kt` |
| Warnings | "Testnet only: send only testnet NEAR" notices | `Screens.kt` Receive, `SeedScreens.kt`; on mainnet replace with "Real NEAR" wording |
| Sponsor | `Onboarding.Sponsor`, `BuildConfig.SPONSOR_*` | testnet only: `MAINNET` has no sponsor and no faucet, and `Onboarding` refuses both there in code, not just in the UI |
| 1Click, on/off-ramp | none | mainnet only; on testnet Swap / Buy / Sell show "Mainnet only" |

`grep -rn testnet android/core/src/main android/app/src/main` lists every
site (about 35 on 14 Nov). [TROUBLESHOOTING.md](TROUBLESHOOTING.md) quotes
several of these strings; update it in the same change.

### 0.2 The network switch (Settings → Network)

```
┌──────────────────────────────┐
│ Network                      │
│ ◉ NEAR Mainnet               │
│   Real NEAR. Swap, buy, sell │
│ ○ NEAR Testnet               │
│   Test NEAR, free from the   │
│   faucet. Not real money     │
│                              │
│ Each wallet lives on one     │
│ network. Switching shows     │
│ that network's wallets.      │
└──────────────────────────────┘
```

When Testnet is on, every screen has an amber strip at the top:
"TESTNET · not real money" (Phantom's testnet mode does the same), and
the wallet switcher shows a network chip on each wallet.

Rules:

| Rule | How |
|---|---|
| A wallet's network is fixed at creation and stored with it | `WalletIndex` entry gains `network`; entries without it are migrated as testnet (every wallet before this change is a testnet wallet). Never inferred from the account name: implicit 64-hex accounts look the same on both networks |
| Switching changes what you see, never a wallet | The wallet list and switcher show only the current network's wallets; the active wallet becomes the last one used on that network, or Welcome ("No mainnet wallet yet: Create / Import / Recover") |
| Each wallet talks to its own network | `AppViewModel` keeps one `NearClient` per network and picks it by the wallet's `network`, not by the switch. The watchtower (`Watchtower.runOnce`) checks every wallet with alerts on, on its own network, whatever the switch says; alerts name the network |
| A setup in progress stays on its network | `Store.setupStep` / `setupPlan` record the network; "Continue setup" appears only on that network |
| Address book per network | `AddressBook` entries get `network` (migrated as testnet); Send only suggests contacts from the wallet's network |
| Lookups use the current network | Recover, Be a guardian, seed import (`SeedWallet.findAccounts`) search only the current network and check its account suffix |
| Mainnet off until the gates pass | `BuildConfig.MAINNET_ENABLED` (from `local.properties`, default false). Off: the Network row shows Mainnet as "Coming soon". Existing installs stay on testnet |
| Default | New installs: Mainnet once enabled; existing installs keep Testnet |

Secrets: the 1Click JWT and the ramp partner key come from
`local.properties` (`tapvault.oneclick.jwt`, `tapvault.ramp.key`), never
from git. The testnet sponsor key can stay in testnet-capable builds (it
only spends testnet NEAR), but leave it out of any public release.

Known gap with one app for both networks: signed messages name the wallet's
account (`domain.rs`, `intent_hash`, `freeze_message`) but not the network.
If the same implicit account were a Tapvault wallet on both networks with
the same card, a card signature from one could be shown to the other
(card-propose and card-freeze challenges have no on-chain seed). It can't
move funds (invariant 11) but could open or freeze. Until a contract adds
the network to the domain separators (new signed bytes, new wallets only),
the app refuses "Protect with bank card" when the same account already
runs a Tapvault contract on the other network.

### 0.3 Creating a wallet on mainnet

No faucet, so the user funds it. What one wallet costs (14 Nov build):

| Item | NEAR |
|---|---|
| Contract storage: 352,170-byte wasm + ~4,000 bytes state at 10^19 yocto/byte (`SeedWallet.storageFor`) | ≈ 3.56, locked for as long as the code is deployed |
| Fee account funding (`Onboarding.Sponsor.GAS_FUNDING`) | 0.5 |
| Token registrations (`storage_deposit`, per token) | ≈ 0.00125 each |

The path that already exists fits: **create or import a seed-phrase wallet
(an implicit account), fund it from an exchange or with Buy, then "Protect
with bank card"** (`SeedWallet.preflight`, `fundFeeAccount`, then the normal
setup). Buy (section 6) can be offered right on the "fund your account" step.
Worth doing before launch: shrink the wasm (`wasm-opt -Oz`, fewer
dependencies) since every byte costs every user 10^-5 NEAR — but that is a
new build and a new hash (invariant 3).

### 0.4 Gates before any real money

1. Real bank cards over real NFC, every flow in [TESTING.md](TESTING.md)
   (not done yet; the main risk in [STATUS.md](STATUS.md)).
2. An outside review of the contract (it holds real funds and can't be
   patched on v0.1 wallets; v0.2+ only through a delayed `UpgradeCode`).
3. `TestnetE2ETest` gets a mainnet twin that runs only by hand, with a
   tiny amount, after 1 and 2.
4. In-app: a first-run notice that this is real money, and small default
   limits.

Read [DESIGN.md](DESIGN.md) for the security model this keeps.

### Step 0: what was built (14 Nov) and what is left

Built, `./gradlew :core:test` passes (`NetworkTest`, 6 cases), APK builds,
not yet run on a phone:

- `Network.kt` (core) holds every network literal; `NearClient(network)`
  takes its RPCs, index and explorer from it.
- `WalletIndex` stores `net_<id>` per wallet (old entries are written as
  testnet on first start), the switch (`network`), the last wallet per
  network (`active_<network>`), and one address book per network (the old
  book is the testnet one; separate keys rather than a field per entry).
- `AppViewModel` keeps one client per network and uses the active wallet's;
  `switchNetwork` changes what is shown; the watchtower checks each wallet
  on its own network and skips mainnet wallets in a build without mainnet.
- Faucet and sponsor refused on mainnet in `Onboarding`; `AppViewModel`
  never passes a sponsor there.
- Mainnet "Create wallet → With my bank card" starts a seed phrase wallet
  (the phrase key's implicit account, created by the user's first deposit),
  then the usual Protect flow funds the fee account from it.
- `SeedWallet.checkNotOnOtherNetwork` refuses to protect an implicit
  account that runs a contract on the other network, and refuses when the
  other network can't be asked.
- Settings → Wallets → Network (`NetworkSheet`), strings per network,
  TROUBLESHOOTING updated, AGENTS invariant 7 reworded.

Different from the text above:

- The testnet strip (`NetworkFrame`) shows only in builds that have mainnet:
  a testnet-only build is testnet everywhere, and its screens say so.
- No network chip on each wallet: the switcher only lists the shown
  network's wallets and says which network that is.

Left before mainnet can be turned on:

- Recover a wallet and Be a guardian on mainnet: this phone's fee account
  comes from the faucet; mainnet needs one the user funds (show its implicit
  address, wait for NEAR). Refused there for now (`requireFaucetForJoin`).
- The gates in 0.4, including the first-run real-money notice and small
  default limits.
- A phone run of: switching back and forth, a testnet wallet's alerts while
  Mainnet is shown, and an upgrade over a pre-switch install (migration).

---

## 1. What the contract allows (and why it shapes everything)

The contract has two actions (`contract/src/types.rs`, `Action`):

- `Transfer { asset: Near | Ft { token }, amount, receiver }`
- `Config { ops }`

Consequences for this plan:

1. **Every swap, bridge and sale is a `Transfer`.** 1Click and off-ramps
   both work by "send X to this deposit address", so nothing needs a new
   action. The phone builds the transfer and computes its hash itself
   (invariant 10); the deposit address comes from 1Click, so the review
   must say plainly that the received amount is 1Click's promise, not
   something the contract enforces.
2. **A token without a limit is never instant** (`execute.rs`, `gate` via
   `is_some_and(... is_instant ...)`): each transfer of it waits
   `delays.transfer` (at least 1 h). A 1Click quote doesn't live that long.
3. **At most 8 limits** (`MAX_LIMITS = 8`): NEAR plus up to 7 tokens can be
   instant. Everything else always waits.
4. **The receiver allowlist (v0.2+)**: once it has an entry, a fresh deposit
   address is never on it, so every swap waits.
5. **An `ft_transfer` to an account not registered on the token fails**;
   `on_transfer` gives the allowance back and emits `transfer_failed`. The
   app must check registration before sending, not after.

Rule that follows: **v1 only offers swap, bridge-out and sell when the
transfer is instant** (asset has a limit, amount within what's left today,
no allowlist or receiver on it). Otherwise the review explains why and
offers "Raise the instant limit" (a `SetLimit` config proposal, waits the
settings delay) or a smaller amount. No delayed swaps in v1: a deposit that
arrives after the quote's deadline is refunded, less fees.

## 2. External services

| Service | Used for | Notes |
|---|---|---|
| FastNEAR API | Which tokens an account holds | Fallback: the app's own list of known tokens + `ft_balance_of` |
| NEAR RPC (existing `NearClient`) | `ft_balance_of`, `ft_metadata`, `storage_balance_of` | View calls, no signing |
| 1Click API (`1click.chaindefuser.com`) | Token list with prices, quotes, deposit addresses, status | Needs a JWT (Bearer). **No testnet**: test on mainnet with small amounts |
| On/off-ramp provider (Transak / MoonPay / Onramper) | Buy and sell for cash | Partner key, KYC by the provider, mainnet only; opened in a Custom Tab with the wallet's account prefilled |

**Verify before building** (from the 1Click OpenAPI spec,
`https://1click.chaindefuser.com/docs/v0/openapi.yaml`):

- Whether native NEAR can be the origin asset, or only wNEAR
  (`nep141:wrap.near`). The wallet can't wrap NEAR today (`near_deposit`
  is a function call with a deposit). If it's wNEAR only, "swap from NEAR"
  waits for v0.4 `Wrap`, and swaps *to* NEAR deliver wNEAR, which the
  wallet holds fine but can't unwrap yet.
- How long a wet quote's deadline can be, and what happens to a late or
  short deposit (refund address = the wallet).
- That a NEAR-side deposit address is registered on the token, or who
  registers it.

No API key or JWT goes into the APK as a secret that matters: 1Click's JWT
identifies the integrator (fees), and an off-ramp partner key is public by
design. Both stay in `local.properties` / `BuildConfig` like the sponsor
fields, never in git (invariant 8).

## 3. Code layout

`android/core` (plain Kotlin, JVM-tested):

| New file | What |
|---|---|
| `Tokens.kt` | `TokenInfo` (contract, symbol, decimals, icon, verified), `TokenBalance`, `TokenList` (known + discovered + custom, hidden set), `ft_metadata` / `ft_balance_of` / `storage_balance_of` via `NearClient.view`, amount formatting with decimals (`BigInteger`, never `Double` for amounts) |
| `Prices.kt` | USD prices from 1Click `/v0/tokens`, cached with a time; "price unavailable" rather than 0 |
| `Intents.kt` | `OneClick` client: `tokens()`, `quote(dry)`, `submitDeposit(address, txHash)`, `status(address)`; `SwapQuote` (amount in, min out, deadline, deposit address, fee); `SwapStatus` |
| `Swap.kt` | `SwapPlan`: from a quote to a `Transfer` action, the instant check (`LimitInfo.isInstant` + allowlist), the receiver registration check, the review text |
| `ChainAddress.kt` | Recognise other-chain addresses (EVM `0x…`, Bitcoin `bc1…`/`1…`/`3…`, Solana base58) so Send can route them through 1Click |

`android/app`: screens in a new `Trade.kt` (Swap, token picker, swap status)
and `Tokens.kt` (token detail, manage tokens, receive); `AppViewModel` gains
`tokens`, `refreshTokens`, `quoteSwap`, `swap`, `sell`, `registerToken`.

Signing stays exactly as today: `TapvaultWallet.proposeByPhone` with the
`Transfer`, `awaitCardChallenge`, `approveCardAndExecute`. No new signed
bytes, so `vectors.json` doesn't change.

## 4. Layout

Bottom tabs: **Wallet · Trade · Activity · Settings** (Trade is new; Earn
waits for v0.4).

```
┌──────────────────────────────┐
│ tv-abc.testnet ▾     🛡       │
│        $1,284.20             │
│        +2.4% today           │
│ [Send][Receive][Swap][Sell]  │
│ ── Tokens ─────────────────  │
│ Ⓝ NEAR     212.4    $1,040   │
│ $ USDC     240.0      $240   │
│ ◎ stNEAR     0.8        $4   │
│ + Manage tokens              │
└──────────────────────────────┘
```

Swap review (then the existing card tap, hold and phone prompt):

```
│ You pay      10 USDC          │
│ You get ≥    1.98 NEAR        │
│ Rate 1 NEAR = 5.02 USDC       │
│ Route   NEAR Intents          │
│ Fee     0.10%                 │
│ Instant ✓ (40 of 50 USDC left)│
│ Quote expires in 0:48         │
│ "Received amount is set by    │
│  NEAR Intents, not your card" │
│ [ Slide to swap ] → tap card  │
```

## 5. Screens

| Screen | Contents |
|---|---|
| Home (changed) | Total in USD, action row Send / Receive / Swap / Sell, token list (hidden and zero balances left out), "Manage tokens" |
| Token detail (new) | Balance and USD value, price, instant limit left today for this token (or "always waits 1 h: no limit set"), Send / Swap / Receive, this token's activity |
| Manage tokens (new sheet) | Search, show/hide, add a custom token by contract (checked with `ft_metadata`, labelled Unverified), "Make instant up to X/day" (proposes `SetLimit`; shows the 8-limit cap) |
| Token picker (new sheet) | Your tokens, popular (1Click list, verified first), search; unverified tokens carry a warning |
| Swap (new) | From / To with a network picker on To (another network makes it a bridge), amount keypad, slippage, review as above |
| Swap status (new) | Waiting for deposit → processing → done, or refunded; explorer links; kept in Activity |
| Receive (new sheet) | QR and account ID; token registration ("USDC: ready to receive" or "Enable USDC", which pays `storage_deposit` from the fee account); "Receive from another chain" (pick chain + token, get a 1Click deposit address that delivers here) |
| Send (changed) | Token selector; recognises other-chain addresses and switches to a bridge-out quote; checks the receiver is registered on the token |
| Buy (new) | Pick NEAR or a token, amount in your currency, provider sheet in a Custom Tab with the account prefilled; afterwards Home shows "Waiting for your purchase" until it arrives. Also offered on the mainnet "fund your account" setup step |
| Sell (new) | Provider sheet in a Custom Tab; the provider's deposit address and amount come back into the normal Send review, labelled "Sale via <provider>" |
| Settings → Network (new sheet) | Mainnet / Testnet (section 0.2); the testnet strip on every screen; network chip in the wallet switcher |
| Activity (changed) | Swaps and sales with their 1Click/provider status next to proposals |

## 6. User flows

### Add or enable a token
1. Manage tokens → search or paste a contract → `ft_metadata` shown.
2. If the wallet isn't registered: "Enable" → the fee account calls
   `storage_deposit({account_id: wallet, registration_only: true})` with
   the token's `storage_balance_bounds.min` (≈0.00125 NEAR). No card needed:
   it's the fee account's own NEAR.

### Send a token
Same as Send today with an asset. Before proposing, `storage_balance_of`
on the receiver; if unregistered, refuse with "This account can't receive
USDC yet" (the fee account could register it, but paying for strangers is a
choice to make later).

### Swap
1. Pick from / to / amount → dry quote (shown live, refreshed every ~10 s).
2. Instant check (section 1). If not instant: explain and offer the
   limit or a smaller amount; stop.
3. Output token registered? If not, register it first (fee account).
4. Slide to swap → wet quote (deposit address, deadline).
5. `proposeByPhone(Transfer(from, amount, depositAddress))` → card tap →
   phone prompt (`KeystoreSigner.reason` names the swap) → execute.
6. `submitDeposit(address, txHash)` → poll `status` until done or refunded.
7. A refund arrives as a normal incoming transfer; Activity says why.

### Bridge out (send to another chain)
Send with an `0x…`/`bc1…`/Solana address, or Swap with another network on
To → 1Click quote with that chain's recipient and `refundTo` = the wallet
→ same steps 4–7. The review shows the destination chain and the full
address with the last 6 characters in bold (as Send does today).

### Bridge in (receive from another chain)
Receive → "From another chain" → chain + token → 1Click deposit address on
that chain with recipient = the wallet → QR. Nothing to sign on the phone.
The deposit address expires; the sheet says when.

### Buy with cash
Home → Buy → token + amount → provider (KYC is theirs) → the provider sends
to the wallet's account. Nothing to sign on the phone. Buying a token: the
app registers the wallet on it first (fee account). The app never sees card
or bank details: they stay inside the provider's page.

### Sell for cash
Sell → provider (KYC is theirs) → provider returns a deposit address and
amount → normal Send review, labelled as a sale → card + phone → the
provider pays out. Same instant rule as swaps.

## 7. Security notes

- **The phone signs what it shows**: the transfer is built and hashed on
  the phone; the review lists asset, amount, deposit address, quote and
  provider. What the phone can't verify is the counterparty's promise; say
  so in one line on the review.
- **A card alone never moves funds**: unchanged, every swap is a transfer
  with the usual approvals.
- **Phone-only wallets (threshold 1)**: the phone alone can swap within the
  instant limit, exactly as it can send today. No new exposure.
- **Unverified tokens** can be any contract: label them, never price them
  from an unverified source, never auto-add tokens someone sent you (spam).
- **Mainnet only for these features**: on Testnet, tokens still work
  (testnet FT contracts exist) but Swap, Buy and Sell show "Mainnet only";
  a testnet wallet never talks to 1Click or a ramp.

## 8. Build order

| Step | What | Testable on |
|---|---|---|
| 0 | `Network.kt`, Settings → Network, per-wallet network + migration, testnet strip, mainnet onboarding via seed wallet + Protect, the gates in 0.4 | JVM tests (migration, network per wallet, sponsor refused on mainnet); all existing tests on testnet unchanged; mainnet by hand with a few NEAR, after real-card testing |
| 1 | `Tokens.kt`, `Prices.kt`; Home token list, Token detail, Add and manage tokens, Receive with Enable token | JVM tests; testnet FTs on a testnet wallet; then mainnet |
| 2 | Send a token, per-token instant limits from Manage tokens | `TestnetE2ETest` with a test FT; then mainnet, small amount |
| 3 | `Intents.kt`, `Swap.kt`; Swap, token picker, swap status | JVM tests with recorded 1Click responses; mainnet, small amounts (1Click has no testnet) |
| 4 | Buy and Sell | mainnet, the provider's sandbox first if it has one |
| 5 | Bridge out via Send, bridge in via Receive | mainnet, small amounts |

Each step: `./gradlew :core:test`, `android/build-apk.sh`, and the
matching part of [TESTING.md](TESTING.md) (add one section per step), on
both networks where the feature exists. When step 0 lands, update
invariant 7 in [../AGENTS.md](../AGENTS.md) to "every wallet has one
network, stored with it; sponsor and faucet are testnet only; mainnet stays
off (`MAINNET_ENABLED`) until real-card testing and a contract review".
