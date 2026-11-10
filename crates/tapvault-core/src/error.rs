use core::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoreError {
    // bigint / RSA
    ModulusZero,
    SignatureNotReduced,
    BadLength,
    // EMV
    EmvBadHeader,
    EmvBadTrailer,
    EmvBadFormat,
    EmvBadHashAlgorithm,
    EmvBadDynamicDataLength,
    EmvBadPadding,
    EmvHashMismatch,
    EmvUnsupportedExponent,
    EmvModulusTooLarge,
    EmvTerminalDataLayout,
    EmvChallengeMismatch,
    // WebAuthn
    WebAuthnAuthDataTooShort,
    WebAuthnRpIdMismatch,
    WebAuthnUserNotPresent,
    WebAuthnUserNotVerified,
    WebAuthnTypeMismatch,
    WebAuthnChallengeMismatch,
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}
