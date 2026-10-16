# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

An Android client for a `pass` / gopass password store kept in Git. It clones over SSH, decrypts with OpenPGP (PGPainless), and commits and pushes edits. It targets one phone (Galaxy S25 Ultra, Android 17) and uses `minSdk` 34 on purpose: the credential provider API arrived in Android 14 and is the reason this app exists.

The build setup is copied from [notes-droid](https://github.com/parham-alvani/notes-droid); its CLAUDE.md explains the toolchain traps (JDK 17 only, AGP 9 built-in Kotlin, never apply `org.jetbrains.kotlin.android`). They all apply here.

## Commands — always `just`

```bash
just doctor    # confirm the toolchain is usable
just ci        # ktlint, android lint, tests, debug build
just test      # unit tests (JVM + Robolectric)
just fmt       # ktlint format
just install   # debug build onto the connected device
just verify-apk
```

## Modules

```text
core/store/    kotlin("jvm")     pass file format, .gpg-id, TOTP, search, generator -- NO Android
core/crypto/   kotlin("jvm")     PGPainless: encrypt, decrypt, key reading, .gpg-id matching -- NO Android
core/data/     android-library   git (JGit + sshd), Keystore sealing, key ring, settings, repository
app/           application       Compose UI, unlock flow, DI wiring
```

Keep Android out of the two JVM modules. Their tests run in seconds, and anything that touches the store format or the cryptography belongs there.

## Rules that matter

- **Never encrypt for fewer recipients than `.gpg-id` names.** `PasswordRepository.recipientsFor` refuses with `MissingRecipients`; do not add a fallback that encrypts for "whoever we have a key for". That silently locks other people out of a shared folder.
- **Parsing an entry and formatting it again must round-trip** (`PassEntryTest`). A phone edit must not rewrite the shape of a file made on the desktop.
- **Commit messages follow `pass`'s own wording** ("Add given password for X to store.", "Remove X from store."), so `pass git log` reads the same whichever device made the change.
- **Full clone, never shallow.** GitHub refuses pushes from a shallow clone.
- **Secrets as `CharArray`, wiped after use.** Do not turn a passphrase into a `String` outside the UI text field.
- **The biometric key is TEE, not StrongBox.** Per-use biometric keys in StrongBox are where the firmware bugs Password Store hit live (its issue #206). The at-rest key tries StrongBox and self-tests it.
- **No test touches a real store.** `.gitignore` refuses `*.gpg` and `*.asc` outside test resources; tests generate their own keys with PGPainless.

## Tests

Robolectric runs against SDK 34 (`core/data/src/test/resources/robolectric.properties`): it needs Java 21 to sandbox SDK 37 and the build is pinned to 17. There is no AndroidKeyStore under Robolectric, so code that seals things takes a `SecretBox` and tests pass a clear one.

## Public-repo hygiene

This repository is public; the store it reads is private. Never commit a key, an entry, a real `.gpg-id`, a remote URL, or a log from a real sync.
