# VK Wave for Android

Android music-player prototype focused on a modern VK-oriented listening experience and seamless track transitions.

## Stack
- Kotlin + Jetpack Compose + Material 3
- AndroidX Media3 ExoPlayer + MediaSessionService
- Dual-player equal-power crossfade engine
- Preferences DataStore for local playback settings
- GitHub Actions build and unit-test pipeline

## Build
Open the project in Android Studio, sync Gradle and run the `app` configuration.

CI builds the debug APK and runs local unit tests with:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

## Current implementation
- Adaptive dark UI scaffold for compact phones and wide screens
- Live playback progress, elapsed/duration display and seeking
- On-demand synthetic two-tone test queue to exercise playback and crossfade without VK credentials
- Activity connects to the foreground service through a lifecycle-managed MediaController
- Two ExoPlayer decks are owned by the playback service
- MediaSession follows the active deck through two stable player adapters
- Audio focus is coordinated by the service, avoiding competing focus requests during overlap
- Headphone-unplug handling is enabled on both decks
- Equal-power cosine/sine crossfade curves with configurable 2–15 second duration (default 7)
- Incoming track pre-buffering and a gapless fallback when the next source is late
- Queue set/add/move/replace/remove operations are routed through the crossfade engine
- Repeat off/all/one behavior and previous/next command availability
- Queue replacement preserves the current playback position and playing state where possible
- Crossfade duration is persisted locally with Preferences DataStore
- Unit tests cover crossfade gain curves, timing boundaries and provider-neutral queue behavior
- Provider-neutral track model keeps authorized stream resolution separate from playback

## Not implemented yet
- VK authentication and account linking
- VK catalog search and authorized stream URL resolution
- Real VK track artwork/catalog data in the UI
- Runtime playback verification on a physical Android device
- VK authentication and catalog integration remain blocked pending a supported VK audio-access route

The app generates two short synthetic WAV tones in its cache when the **Тест движка** action is used; no copyrighted sample music is bundled. This test queue exercises MediaSession, queue commands, seeking, progress and crossfade.

## VK integration gate

VK's public audio API for third-party applications was disabled in 2016, while the official VK ID SDK provides OAuth authentication rather than a guarantee of music-catalog access. Before implementing real VK music, the project needs a supported access path from VK (for example, approved partner/official-client access). The project will not rely on undocumented private endpoints, stolen tokens or official-app impersonation.

VK credentials are not required for the current playback foundation. If a supported VK ID integration is approved, app configuration can be added at that stage. No client secrets or access tokens belong in this public repository.

Application ID: `com.vkwave.app`
