# Contributing to JARVIS Music

Thanks for your interest in improving JARVIS Music! This project is part of the JARVIS ecosystem by [CodeWithLakxsh](https://github.com/CodeWithLakxsh).

## Getting started

1. Fork the repository and create a branch: `git checkout -b feat/your-feature`.
2. Make your changes against `main`.
3. Build and test your change locally before submitting.
4. Open a pull request using the [pull request template](.github/pull_request_template.md).

## What to work on

- Look for open issues and the `good first issue` label.
- Keep changes focused and small; prefer several PRs over one large one.
- Backend (`api/`) and Android app (`app/`) changes are both welcome.

## Code style

- Android: follow existing Kotlin conventions (Kotlin style `official`, ViewBinding, coroutines over callbacks).
- Backend: keep the Flask API thin; return JSON lists as the app expects.
- Do not add dependencies unless they are clearly necessary.

## Reporting bugs / requesting features

Use the issue templates:

- [Bug report](.github/ISSUE_TEMPLATE/bug_report.yml)
- [Feature request](.github/ISSUE_TEMPLATE/feature_request.yml)

## Before you commit

- Never commit secrets, `.env` files, build outputs (`app/release/`, `*.apk`), or local IDE files.
- Rebuild the project to confirm nothing is broken.
- Update the README only if the change affects documented behaviour.

## Questions

Open a discussion or ask in your pull request. All contributors must respect our [Code of Conduct] (placeholder — to be added).
