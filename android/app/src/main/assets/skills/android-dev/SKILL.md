# Android Dev

Guidance for writing Android code with Mezchaju agents.

- Use Kotlin + Jetpack Compose or XML views; match the repo's existing stack.
- Prefer Material 3 components and edge-to-edge layouts.
- Handle permissions at runtime (API 23+) and lifecycle-aware coroutines.
- Keep network calls off the main thread; use WorkManager for background jobs.
- Use Gradle Kotlin DSL (`build.gradle.kts`); target SDK must match the project.
