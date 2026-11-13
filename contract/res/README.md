# Contract build shipped by the app

`tapvault_contract.wasm` is the reviewed build of `contract/` that the Android
app deploys to every new wallet. It is committed so the app can be built
without the Rust toolchain and always carries exactly this code.

| | |
|---|---|
| SHA-256 (hex) | `bbb34d0898d18ab7bb8687c61ba8605def6e7c9a33be91659b199d0834ca2f1c` |
| SHA-256 (base58, NEAR `code_hash`) | `DdhpDh3c2gbVJK4iFQLU7C3c2W4Bgg6JYTBSBtBDRcS7` |
| Size | 352,170 bytes (contract v0.3.0: phone-only wallets, plus v0.2) |
| Built with | rustc 1.86.0, cargo-near 0.17.0, `cargo near build non-reproducible-wasm` |

The Android build (`android/app/build.gradle.kts`, task `verifyWasm`) refuses
to package a file with a different hash. After changing the contract:

```bash
cd contract && cargo near build non-reproducible-wasm
cp ../target/near/tapvault_contract/tapvault_contract.wasm res/
shasum -a 256 res/tapvault_contract.wasm   # put this in android/app/build.gradle.kts
```

and update the base58 hash in the top-level README. A non-reproducible build
embeds local paths, so rebuilding on another machine gives a different hash
even from the same source; keep this file unless the contract really changed.
Existing wallets keep the code they were deployed with.
