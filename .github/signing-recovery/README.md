# Android release signing recovery

This directory contains **encrypted recovery material only** for the custom Japanese security release line.

## v1.2.0-ja-sec1

- APK signing certificate SHA-256: `A9:41:F3:7C:C4:A9:C6:3B:6C:6E:AA:12:CD:C0:08:2E:4E:F0:30:04:C1:8B:EC:BC:A8:C4:EF:DF:BC:DA:05:F1`
- Signing key: RSA 3072-bit, alias `dock-ja-sec1`
- The encrypted CMS payload is stored as Base64 text in `release-signing-recovery-v1.2.0-ja-sec1.cms.b64`.
- The private recovery key is **not** stored in this repository. Keep it offline with the corresponding private backup.

To decrypt the stored recovery bundle on a trusted machine:

```bash
base64 -d release-signing-recovery-v1.2.0-ja-sec1.cms.b64 > recovery.cms
openssl cms -decrypt -binary -inform DER \
  -in recovery.cms \
  -recip ../release-recovery-cert.pem \
  -inkey /path/to/recovery-private.pem \
  -out recovery.tar.gz
tar -xzf recovery.tar.gz
```

Before future automated releases, configure the recovered keystore and credentials as the repository Actions secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The release workflow must never fall back to a debug key.
