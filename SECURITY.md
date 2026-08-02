# Security Policy

JARVIS Music is a hobby project and is provided as-is. We take security reports seriously and will address valid issues as soon as possible.

## Reporting a vulnerability

Do **not** open a public issue for a security problem. Instead, report it privately:

- Open a private advisory on GitHub: **Security → Report a vulnerability**
- Or email the maintainer via the contact details on the [GitHub profile](https://github.com/CodeWithLakxsh).

Please include:

- A description of the vulnerability and its impact.
- The affected file(s)/version(s).
- Reproduction steps, if possible.
- Any suggested fix (optional).

## Handling

- We will acknowledge receipt within a few days.
- We will work on a fix and credit you in the release notes if you wish.
- Please allow time for a fix to land before public disclosure.

## Scope

- The Android application (`app/`).
- The Flask backend API (`api/`).

## Known considerations

- The Android app currently enables cleartext traffic and trusts user-added certificates (`app/src/main/res/xml/network_security_config.xml`). This is fine for a hobby app but should be reviewed before any production distribution.
- The Flask backend generates a random session key per process when `SECRET` is not set. Set a stable `SECRET` in production.

## Safe handling of secrets

- The API signing key is read from the `SECRET` environment variable — never commit a real `.env` file.
- Keep API/cloud credentials out of the repository (see `.gitignore`).
