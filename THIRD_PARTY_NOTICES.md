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
the optional Flutter frontend in `flutter/`, under the same author and license. It is
not part of the shipped release APK: the Flutter module is opt-in (`-PflutterUi=true`)
and releases still ship the Compose frontend.

## FlClash

**FlClash** by chen08209 was used as the behavioral reference for the anchored menu
popup motion:

- https://github.com/chen08209/FlClash
- Licensed under the GNU General Public License, version 3

## Other dependencies

AndroidX / Jetpack Compose (Apache License 2.0), Haze by Chris Banes (Apache License 2.0)
and the remaining dependencies keep their own licenses; see the dependency declarations
in `app/build.gradle.kts` and `gradle/libs.versions.toml`.
