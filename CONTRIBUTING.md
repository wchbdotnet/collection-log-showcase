# Development and releases

The standalone repository is https://github.com/wchbdotnet/collection-log-showcase, with `main` as its release branch. Plugin author metadata must remain **IVIystical**.

Use Java 11 and run `./gradlew clean build` before preparing a release. `./gradlew run` starts the development client. Follow the current [Plugin Hub requirements](https://github.com/runelite/plugin-hub).

The initial release is **1.0.0**. For each future update PR, increment the patch version (1.0.1, 1.0.2, and so on) in both `build.gradle` and `runelite-plugin.properties`. Commit and push the release, then update the Plugin Hub manifest to that exact commit. Do not change the version again merely to revise the same pending PR.

Keep the upstream and audio-source attribution and licences intact. Test real unlocks, screenshots, audio and CoX release in-game when changing those behaviours.
