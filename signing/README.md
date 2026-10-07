# Release signing

Release APKs are signed in CI (`.github/workflows/build.yml`) with APK Signature Scheme v3 key rotation:

- Old key (alias `kncloud`, cert SHA-256 `41:5E:AC:55:…:6C:56`): leaked, kept only so existing installs accept the update.
- New key (alias `kncloud2026`, cert SHA-256 `2c9608f219de706a6bb254029f636777dc9ac7d81bdab8cb02d2acd836163299`).
- `lineage.bin` proves old → new. It holds only public certificates and is safe to commit.

Keys live only in GitHub Actions secrets: `KN_OLD_KEYSTORE_BASE64`, `KN_OLD_KEYSTORE_PASSWORD`, `KN_NEW_KEYSTORE_BASE64`, `KN_NEW_KEYSTORE_PASSWORD`.
Never commit a keystore or a password.

Android 9+ devices switch to the new key after this update. Android 7–8 only check the old key, so on those devices a fake APK signed with the leaked key can still install over KNcloud.
