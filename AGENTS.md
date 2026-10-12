# Plugin development conventions

- Author metadata must be IVIystical.
- Follow https://github.com/runelite/plugin-hub and its current review requirements.
- Keep Java 11 compatibility, use gameval constants, and do not use reflection or automate game input.
- Keep network and disk work off the client thread; clean up overlays and listeners on shutdown.
- Preserve upstream licensing and attribution.
- Build and run relevant tests before delivery. In-game behavior requires manual user verification.
- After every requested change, launch the updated development client. Assume the user closed the prior client when sending feedback and screenshots. Plugin Hub submission preparation is authorised; opening the upstream submission requires an explicit request to submit.

- Release 1.0.0 is the initial Showcase release. Increment the patch version for each future update PR (1.0.1, 1.0.2, etc.). Keep build.gradle and runelite-plugin.properties versions in sync.
