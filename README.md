# Expense Tracker — releases and updates

This repository publishes **Expense Tracker**, the reference application xPack
is validated against. Its source is in the xPack repository, under
[`examples/expense-tracker`](https://github.com/Zaaim-Halim/xPack/tree/main/examples/expense-tracker).

Nothing here is built by hand:

- **Releases** hold each version: the signed package (`.xpkg`), deltas from
  earlier versions (`.xpkgd`) and the installer.
- **GitHub Pages** (the `gh-pages` branch) serves the update index installed
  copies read:
  `https://zaaim-halim.github.io/expense-tracker-releases/updates/<platform>/stable.json`

An installed copy checks that index, downloads the new version (a delta when it
can), verifies it against the key it pinned when it was installed, and
switches to it on its next start. Packages are signed with a key that never
leaves the publisher's machine.

To install: download `Install-Expense-Tracker-…` for your platform from the
latest release. Builds are not code-signed yet, so macOS quarantines a browser
download and Windows SmartScreen flags it.
