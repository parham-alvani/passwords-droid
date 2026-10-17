<div align="center">
   <img src="art/icon-512.png" alt="A panda hugging a golden padlock and holding a key" width="160">
   <h1>Passwords Droid</h1>
   <img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/parham-alvani/passwords-droid/ci.yaml?style=for-the-badge&logo=github">
   <img alt="License" src="https://img.shields.io/github/license/parham-alvani/passwords-droid?style=for-the-badge">
</div>

## Introduction

An Android client for a [`pass`](https://www.passwordstore.org/) / [gopass](https://www.gopass.pw/) password store kept in a Git repository. It clones the store over SSH, decrypts entries with your OpenPGP key, and commits and pushes what you change on the phone — the same files, the same `.gpg-id` rules and the same commit messages as `pass` on the desktop.

It is written for one modern phone rather than every phone: Android 14 and newer, Material You, and the phone's secure hardware for everything it keeps.

## Status

Early, and used daily on one phone. Set up, sync, browse, search, open, copy, edit, add, rename and delete work, with fingerprint unlock, and passwords fill into apps and Chrome. Passkeys come next (see [Roadmap](#roadmap)).

## Features

- **Autofill everywhere** — a classic autofill service for Chrome and apps, and a Credential Manager provider for Android's own sign-in sheet; suggestions appear in the keyboard, each stays locked until a fingerprint opens just that entry, and a login typed into a form can be saved as a new entry
- **Matching by name, nothing decrypted** — a site matches the folder named after its domain (`work/github.com/me` fills on `github.com` and its subdomains), an app through its package read as a reversed domain; lookalikes such as `evilgithub.com` match nothing, and a browser is only believed about the page when it is a known, correctly signed browser
- **Folders and icons** — browse the store as folders; top-level folders carry Lucide icons kept in `.icons.json`, which gopass ignores
- **Wi-Fi as a QR code** — entries under `wifi/` (or with an `ssid` field) show a code a guest's camera joins from
- **Password health** — weak (zxcvbn), reused and years-old passwords, plus an opt-in breach check that sends Have I Been Pwned only the first 5 characters of each password's SHA-1
- **History** — every version of an entry from git, and restoring one as a new commit
- **Files** — certificates, keys and VPN profiles open as files with copy and save-as; any file can be added, as text or as a gopass attachment
- **gopass templates** — a folder's `.pass-template` shapes new entries in it, as `gopass generate` does
- **`gopass create` wizards** — a new entry can start from the store's `.gopass/create` templates, or gopass's own (website login, PIN, SSO / passwordless login) when it has none; the name is built from the answers as gopass builds it, and taken names ask before overwriting
- **SSO and social logins** — an entry with no password and a `login-method` (Google, Apple, GitHub, Microsoft, SSO, magic link) shows "Sign in with Google" instead of a password, fills only the username, and is left out of the health check
- **Sync status** — changes waiting to be pushed, a failed push or a conflict show at the top of the list, with a retry
- **Recipients** — see who a `.gpg-id` names and re-encrypt a folder or the whole store for a new set, as one commit
- **Compatible with `pass` and gopass** — the first line is the password; `key: value` lines, gopass's YAML body and `otpauth://` URIs are understood; files are written back in the same shape they were read in
- **Every recipient, every time** — an entry is encrypted for everyone in the nearest `.gpg-id`, using imported keys and gopass's exported `.public-keys`; a save that would leave a recipient out is refused instead
- **Git over SSH** — the deploy key is generated on the phone and never leaves it; GitHub's host keys are pinned; pulls rebase, so history stays a straight line; an edit made offline is committed and pushed with the next sync
- **Fingerprint unlock** — the OpenPGP passphrase is sealed by an AndroidKeyStore key that only a strong biometric can use, for one operation at a time, and that Android destroys when a new fingerprint is enrolled
- **StrongBox when it works** — the at-rest key goes into the phone's separate security chip (Knox Vault on a Galaxy) after a round-trip self-test, and falls back to the TEE on firmware that cannot use it
- **Keys sealed at rest** — imported OpenPGP keys are wrapped by a hardware key, so a copy of the app's files is useless off the phone; there is no backup of any kind
- **One-time codes** — TOTP (RFC 6238) with a live countdown
- **Careful clipboard** — copies are marked sensitive so the keyboard and the system preview do not show them, and are cleared after a timeout unless something else was copied since
- **Fuzzy search** over the whole store, ranked like a fuzzy finder
- **Password generator** that guarantees every requested character class
- **No screenshots** — `FLAG_SECURE` on every screen, a blank card in Recents
- **Imports keys as you have them** — an armored or binary export, or a backup locked with a code (OpenKeychain's "Backup key", `gpg --symmetric`)

## Setup

1. Export your secret key, locked with a one-time code for the trip to the phone:

   ```bash
   gpg --armor --gen-random 1 24   # a code for the next step
   gpg --armor --export-secret-keys <key id> | gpg --armor --symmetric --output key.sec.asc
   ```

2. Install the app, import `key.sec.asc` and enter the code.
3. Enter the store's Git URL (for example `git@github.com:you/passwords.git`), generate the SSH key, and add it to the repository as a deploy key with **Allow write access** ticked.
4. Clone.
5. In **Settings → Passwords, passkeys and autofill**, choose Passwords as the preferred service. For Chrome, also turn on **Chrome → Settings → Autofill services → Autofill using another service**; without it Chrome fills from its own password manager only.

## Roadmap

- [x] Credential Manager provider for passwords
- [x] Autofill service for Chrome and apps
- [ ] Passkeys stored in the store, compatible with [Password Store's format](https://github.com/agrahn/Android-Password-Store/blob/main/PasskeyStorage.md)
- [ ] Quick Settings tile and home-screen widget for one-time codes
- [x] Wi-Fi QR codes, password health, entry history, files, templates, recipients
- [ ] Diceware passphrases
- [ ] OpenPGP smart cards over NFC (YubiKey)

## Building

Everything goes through [`just`](https://github.com/casey/just):

```bash
just doctor    # check the toolchain
just ci        # lint, tests and a debug build -- what CI runs
just install   # onto the connected device
```

The debug build is `me.parham1995.passwords.debug` and installs next to a release build, so trying a change never touches the copy holding real keys.

## Acknowledgements

The design owes a lot to [Password Store](https://github.com/agrahn/Android-Password-Store) (GPL-3.0), whose `pass` compatibility, Keystore passphrase caching and passkey format were the reference for this one. Its `autofill-parser` is vendored unchanged in [`autofill-parser/`](autofill-parser/) (LGPL-3.0 with linking exception), and the privileged-browser lists come from Google and the Bitwarden community via Password Store.

## License

GPL-3.0. See [LICENSE](LICENSE).
