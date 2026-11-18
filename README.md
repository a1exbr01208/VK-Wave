# VK Wave for Android

Android music player with a provider-neutral playback core and seamless track transitions.

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

## Playback foundation
- Adaptive dark UI scaffold for compact phones and wide screens
- Live playback progress, elapsed/duration display and seeking
- On-demand synthetic two-tone test queue for exercising playback without external credentials
- Foreground MediaSession service with lifecycle-managed MediaController
- Local wake lock while audio is actively playing with the screen off
- Two ExoPlayer decks with stable MediaSession player adapters
- Service-coordinated audio focus and headphone-unplug handling
- Equal-power cosine/sine crossfade, configurable from 2 to 15 seconds (default 7)
- Incoming-track pre-buffering and gapless fallback when the next source is late
- Failed stream candidates are skipped during automatic transitions; queue entries remain available for manual retry
- Queue set/add/move/replace/remove operations, repeat off/all/one
- Persisted crossfade preference

## Universal music source architecture
- `MusicSource` is the contract for every catalog/provider integration
- `MusicSourceRegistry` registers providers by stable source ID
- `WaveTrack` carries provider identity without coupling the playback engine to a provider
- Each source resolves its own track into a playable Android URI
- `MediaItemFactory` converts resolved tracks into the common Media3 queue format
- Capability and connection-state models let the UI adapt to search, library, authentication and permissions
- `LocalAudioSource` reads the device's indexed music through Android MediaStore and resolves local content URIs
- `AudiusMusicSource` uses the official read-only Audius REST API for catalog search and stream URLs; no user login is required for the initial public endpoints
- `InternetArchiveMusicSource` searches public audio items and exposes only recordings marked Public Domain or CC0; it checks item metadata and selects a supported audio file before playback
- `JamendoMusicSource` uses the official Jamendo API v3 for independent-music search and playback redirects; enter its public Client ID in the in-app Settings screen or provide `JAMENDO_CLIENT_ID` in local `local.properties`
- Search now runs one query concurrently across every registered provider with search capability and displays results grouped by source
- Local audio requests Android audio-library permission at runtime and exposes a browsable device library

### Planned source adapters
- VK Music, only if a supported access route becomes available
- SoundCloud, subject to official app registration / API access and attribution requirements
- Spotify remote-control adapter (not an in-app audio source; Spotify policies prohibit crossfading its content)
- Other authorized streaming providers
- User-owned local files and future document-provider imports

No provider is allowed to inject undocumented endpoints or bypass account protections. The public VK Audio API is not generally available to third-party apps, and VK ID OAuth alone does not grant catalog access. VK remains an optional adapter, not a dependency of the player.

## Not implemented yet
- SoundCloud OAuth / app credentials, if access is granted
- Physical-device playback verification for Audius and Internet Archive streams
- Jamendo live search and stream verification after a valid Client ID is configured
- Real provider authentication and personal-library features where supported by an official API

The **Тест движка** action generates two original synthetic WAV tones in the app cache. No copyrighted sample audio is bundled.

## Security
The GitHub repository is public. Never commit API secrets, access tokens, passwords or private user data. Provider adapters must use supported authorization and playback routes.

Application ID: `com.vkwave.app`
