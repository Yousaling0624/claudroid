# Third-party notices

Claudroid is distributed under the GNU General Public License, version 3. See
[`LICENSE`](LICENSE).

## prism_ui

The settings picker's liquid morph — the metaball/goo outline geometry and the popup
motion constants — is ported from **prism_ui** by chen08209:

- https://github.com/chen08209/prism_ui
- Copyright (C) chen08209
- Licensed under the GNU General Public License, version 3

`prism_ui` is also consumed as a Dart dependency (`prism_ui: 0.1.2` from pub.dev) by
the Flutter frontend in `flutter/`, under the same author and license. That frontend
is the app's only UI and is part of every build, so `prism_ui` is part of the
distributed binary as well; both sides are GPLv3, so the obligations of the license
are met by the project's own GPLv3 terms.

## FlClash

**FlClash** by chen08209 was used as the behavioral reference for the anchored menu
popup motion and the settings-page structure. The `key`, `cpu`, `paintbrush` and
`sliders` glyphs in `flutter/lib/ui/glyphs.dart` are ported from FlClash's
`AppGlyphs` (`lib/ui/icons/app_glyphs.dart`):

- https://github.com/chen08209/FlClash
- Copyright (C) chen08209
- Licensed under the GNU General Public License, version 3

## Host-side bridge (not part of the APK)

The optional remote-host daemon in `bridge/` is installed separately on the user's own
host and is not bundled in the Android app. Its npm dependencies keep their own terms:

- **@anthropic-ai/claude-agent-sdk** — © Anthropic PBC, all rights reserved; use is
  subject to Anthropic's legal agreements (<https://code.claude.com/docs/en/legal-and-compliance>).
  The daemon uses it to run the host's own Claude Code installation.
- **TypeScript** (Apache License 2.0), **vitest**, **tsx**, **@types/node**
  (MIT) — build and test tooling only.

## Other dependencies

AndroidX Core / DataStore (Apache License 2.0), NanoHTTPD (BSD 3-Clause), Kotlinx
Serialization and Coroutines (Apache License 2.0) keep their own licenses; see the
dependency declarations in `app/build.gradle.kts` and `flutter/pubspec.yaml`.
