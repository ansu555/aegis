//! NEP-297 event logs, so indexers and the app can follow a wallet.

use near_sdk::env;
use near_sdk::serde_json::{json, Value};

pub fn emit(event: &str, data: Value) {
    let log = json!({ "standard": "tapvault", "version": "1.0.0", "event": event, "data": [data] });
    env::log_str(&format!("EVENT_JSON:{log}"));
}
