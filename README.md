# VK Wave for Android

Android music-player prototype focused on a modern VK-oriented listening experience and seamless track transitions.

## Stack
- Kotlin + Jetpack Compose + Material 3
- AndroidX Media3 ExoPlayer + MediaSessionService
- Dual-player equal-power crossfade engine
- GitHub Actions build and unit-test pipeline

## Build
Open the project in Android Studio, sync Gradle and run the `app` configuration.

CI can build the debug APK and run local unit tests with:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

## Current implementation
- Adaptive dark UI scaffold for compact phones and wide screens
- Crossfade duration control from 2 to 15 seconds, default 7 seconds
- Equal-power cosine/sine gain curves
- Incoming track pre-buffering on the secondary ExoPlayer
- Transition duration is clamped to the remaining outgoing-track duration
- Unit tests cover gain curves and transition timing boundaries
- MediaSessionService foundation with Android media-session integration
- Demo track list remains UI-only; no audio is bundled
- The UI is not yet connected to the playback service or crossfade engine
- VK authentication, catalog access and authorized audio-source resolution are not implemented

## Next engineering milestones
1. Connect the foreground service, media session and UI through MediaController.
2. Integrate the dual-player crossfade engine with the service without breaking system media controls.
3. Add deterministic tests for queue changes, pause/resume, failed sources and interruptions.
4. Verify the currently supported VK authentication and audio-catalog access paths before implementing account integration.
5. Add persistence for queue, favorites, playlists and playback settings.

VK credentials are not required for the current foundation work. No API secrets belong in this public repository.

Application ID: `com.vkwave.app`
