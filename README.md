# drip-factory
Idle melting game for Android: melt ice, chocolate and honey, fill jars, merge burners and upgrade your factory. Native Kotlin + OpenGL ES 3D, no engine.
# Drip Factory

An idle "satisfying" game for Android. Melt blocks of ice, chocolate and honey on a hot plate, catch the drips in glass jars, sell them on a conveyor belt and upgrade your factory.

Built from scratch in **Kotlin** with a custom **OpenGL ES 2.0** renderer (no game engine). Everything, including the 3D shapes and the sound effects, is generated in code.

> Status: early prototype. Expect rough edges.

## Features

- 3D scene: melting block, hot plate, glass jar, conveyor belt, lighting and fog
- Three materials: water, chocolate and honey (more value, slower flow)
- Burners with a merge mechanic: tap two equal burners to merge them into a stronger one
- Upgrades: jar size, value, auto-sell, next material
- Progress is saved automatically
- Generated sound effects (can be muted)

## How to play

1. Tap the ice to melt it faster. Drips fall into the jar.
2. When the jar is full, tap anywhere to sell it (or buy **Auto-sell**).
3. Spend money on burners, jar size and value.
4. Merge two burners of the same level to make a higher level one.
5. Unlock chocolate and honey for bigger profits.

## Download

Get the latest APK from the [Releases](../../releases) page.

If Android blocks the install, allow installing from your browser or file manager when prompted. Debug builds are signed with a temporary key, so uninstall an older build before installing a new one.

## Build it yourself

The easiest way needs no PC: push the project to GitHub and let **GitHub Actions** build the APK.

1. Open the **Actions** tab and run **Build APK** (or push a commit).
2. When the run turns green, open it and download the `drip-factory-debug` artifact.
3. Unzip it to get the `.apk`.

Or locally with Gradle 7.6.4 and JDK 17:

    gradle assembleDebug

The APK is created in `build/outputs/apk/debug/`.

## Project structure

| Path | Purpose |
| --- | --- |
| `src/MainActivity.kt` | Game logic and the 2D overlay (HUD, buttons) |
| `src/Gl3D.kt` | OpenGL ES renderer, camera, procedural meshes, shaders |
| `src/Sfx.kt` | Sound effects generated in code |
| `res/drawable/ic_launcher.xml` | App icon (vector) |
| `.github/workflows/build.yml` | APK build workflow |

## Credits

Inspired by the idle melting and filling genre. This is an independent project and is not affiliated with any commercial game or publisher.

## License

Choose a license (for example MIT) and add it to the repo.
