# Durable release signing — authorized reset, November 19 2026

## Current state

The original v1.1.1–v1.1.6 key was lost with the previous workspace. The owner explicitly authorized a NEW key. No new key has been created yet: initialization is blocked until a durable secret and owner-held recovery password are available. The existing release-cert.sha256 still identifies the OLD signer until initialization succeeds. Never describe this setup as completed merely because code exists.

## One owner action required

1. Generate a random password of at least 32 characters (prefer 64) using a password manager. Save it in that manager with a private backup. Do not post it in chat, workflow inputs, issues or Git.
2. Open https://github.com/mouadknight0-cell/ManhwaLens-v2/settings/secrets/actions
3. Choose **New repository secret**, name it **SIGNING_BACKUP_PASSWORD**, and store that same password as its value. This session's GitHub integration receives HTTP 403 on the Secrets API and cannot create it for you.
4. Tell the agent only that the secret is saved and privately backed up. Do not send its value.

## One-time initialization

Run `Sign verified release` (`sign-release.yml`) on the CURRENT working branch, operation `initialize`, with `backup_confirmed=true` only after the owner confirms step 4. The acknowledgement includes accepting that the new signer cannot update v1.1.6 in place.

The runner generates a 3072-bit RSA PKCS12 key, alias manhwalens, with a separate random internal keystore password. AES-256-GCM encrypts the key and internal password together. PBKDF2-HMAC-SHA256 (600,000 rounds, fresh salt) derives its encryption key from SIGNING_BACKUP_PASSWORD. Initialization verifies decryption before committing only:

- `signing/release-key.enc.json`: authenticated ciphertext, portable across workspaces;
- `signing/release-cert.sha256`: new PUBLIC certificate fingerprint;
- `signing/legacy-release-cert.sha256`: previous PUBLIC fingerprint for audit.

It pushes only to the same workflow branch. A failed push is a failed initialization; do not sign from temporary output. Existing ciphertext is never overwritten. Keep an independent private copy of the encrypted file and the password; GitHub Actions secrets cannot be read back. Losing both the secret and its private backup loses the key again. This is durable storage, not a guarantee against account/repository loss.

## All later releases

Build/unit-test on GitHub with `release.yml` first. It creates an unsigned PRIVATE draft. Run `sign-release.yml` on a branch containing the committed vault, operation `sign`, tag set to that draft. It decrypts only in runner memory/temporary storage, signs, verifies the pinned certificate and APK package/version, and then publishes. There is no fresh-key or ephemeral-envelope fallback.

Keep package ID `com.manhwalens.app` and monotonically increasing versionCode. The new identity cannot directly update v1.1.1–v1.1.6. Do not silently uninstall, clear data or claim migration support; a separate data-preservation plan must be verified. New-to-new update compatibility starts only with the first successfully published APK using this vault.

For recovery, preserve ciphertext and the independent password. `.github/scripts/signing_vault.py` exposes `load_material()` for controlled tooling; never print its return value. To rotate the encryption password, re-encrypt the SAME key after verified recovery; never regenerate an Android signing identity. Password rotation tooling is not implemented here.

The older `send_envelope.py` is historical and no longer used by the signing workflow.
