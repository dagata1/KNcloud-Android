# Release signing

Release APKs are signed in CI (`.github/workflows/build.yml`) with APK Signature Scheme v3 key rotation:

- Old key (alias `kncloud`, cert SHA-256 `41:5E:AC:55:…:6C:56`): leaked, kept only so existing installs accept the update.
- New key (alias `kncloud2026`, cert SHA-256 `2c9608f219de706a6bb254029f636777dc9ac7d81bdab8cb02d2acd836163299`).
- `lineage.bin` proves old → new. It holds only public certificates and is safe to commit.

Both keystores and their passwords are in `keys.tar.gz.enc`, encrypted with AES-256 (openssl, pbkdf2, 600000 iterations).
The passphrase lives only in the GitHub Actions secret `KN_SIGNING_PASSPHRASE`. Never commit a plain keystore or password.

Decrypt locally: `openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in keys.tar.gz.enc | tar -xz`

Android 9+ devices switch to the new key after this update. Android 7–8 only check the old key, so on those devices a fake APK signed with the leaked key can still install over KNcloud.
