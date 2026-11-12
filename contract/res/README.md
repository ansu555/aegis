# Contract build shipped by the app

`tapvault_contract.wasm` is the reviewed build of `contract/` that the Android
app deploys to every new wallet. It is committed so the app can be built
without the Rust toolchain and always carries exactly this code.

| | |
|---|---|
| SHA-256 (hex) | `3ea83cc88fecda3c727d624e5a935ed1fe20cc5623cafafa4e86969bf7cdc2ec` |
| SHA-256 (base58, NEAR `code_hash`) | `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf` |
| Size | 334,258 bytes |
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
