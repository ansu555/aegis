# Contract build shipped by the app

`tapvault_contract.wasm` is the reviewed build of `contract/` that the Android
app deploys to every new wallet. It is committed so the app can be built
without the Rust toolchain and always carries exactly this code.

| | |
|---|---|
| SHA-256 (hex) | `778a310ad4db8a87d6d0d52e8509d7ea4f5014782a0832106ab1cd2946e582c6` |
| SHA-256 (base58, NEAR `code_hash`) | `93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy` |
| Size | 369,809 bytes (contract v0.4.0: inheritance, plus v0.3 phone-only wallets and v0.2) |
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
