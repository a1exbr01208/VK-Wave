# VK Wave for Android

Initial Android prototype v0.1.0.

## Stack
- Kotlin + Jetpack Compose + Material 3
- AndroidX Media3 ExoPlayer
- Dual-player equal-power crossfade engine

## Build
Open in Android Studio (Hedgehog or newer), sync Gradle, run the app configuration.

## Current state
- Dark player UI scaffold
- Crossfade duration control: 2–15 seconds, default 7
- Dual-player equal-power crossfade source
- Demo track list is UI-only; no audio is bundled
- VK catalog/auth integration is not implemented yet

## Next milestones
1. Implement a supported VK OAuth flow and verify audio catalog availability.
2. Connect authorized playable media URIs to CrossfadeEngine.
3. Add foreground media playback service, notification and audio focus.
4. Persist queue, favorites, playlists and settings.
5. Add tests for transitions, interruptions and track changes.

Application ID: com.vkwave.app
