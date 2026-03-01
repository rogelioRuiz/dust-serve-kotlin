# Contributing to dust-serve-kotlin

Thanks for your interest in contributing! This guide will help you get set up and understand our development workflow.

## Prerequisites

- **JDK 17** or later
- **Android SDK** (API 36) with build tools
- **Git**

Unit tests run on the JVM and do not require an emulator.

## Getting Started

```bash
# Clone the repo (along with dust-core-kotlin sibling)
git clone <repo-url>
cd dust-serve-kotlin

# Build
./gradlew build

# Run tests
./gradlew test
```

> **Note:** This project depends on `dust-core-kotlin` as a local project dependency. Make sure `dust-core-kotlin` is available as a sibling directory or adjust `settings.gradle` accordingly.

## Project Structure

```
src/main/kotlin/io/t6x/modelserver/
  # Core state management
  ModelRegistry.kt                  # Thread-safe model descriptor store
  ModelStateStore.kt                # Thread-safe model status tracking
  ModelSessionFactory.kt            # Interface for creating inference sessions

  # Session management
  SessionManager.kt                 # LRU cache with priority eviction, ref counting

  # Download infrastructure
  DownloadManager.kt                # Orchestrates downloads with SHA256 verification
  DownloadDataSource.kt             # Interfaces for download + disk space
  HttpDownloadDataSource.kt         # HTTP download with chunked I/O
  ModelDownloadWorker.kt            # WorkManager background downloads (resumable)
  WorkManagerDownloadCoordinator.kt # Bridges DownloadManager ↔ WorkManager

  # Accelerator & performance
  AcceleratorProbe.kt               # NNAPI/GPU/CPU compatibility probing
  DeviceBenchmark.kt                # Device performance classification
  AcceleratorTypes.kt               # Accelerator and DeviceTier enums
  ProbeInferenceEngine.kt           # Interface for probe inference
  ProbeResultStore.kt               # Caching probe results (SharedPreferences / in-memory)

  # Network & lifecycle
  NetworkPolicyProvider.kt          # WiFi-only / metered network policy
  PostDownloadOrchestrator.kt       # Auto-probe after model download

  # Error handling
  DustCoreErrorExtensions.kt          # DustCoreError → Map conversion

src/test/kotlin/io/t6x/modelserver/
  AcceleratorProbeAndBenchmarkTest.kt  # 14 tests
  SessionManagerTest.kt               # 21 tests
  DownloadManagerTest.kt              # 15 tests
  ModelServerRegistryTest.kt          # 10 tests
  MockModelSession.kt                 # Test helper
  MockDownloadDataSource.kt           # Test helper with configurable failure modes
```

## Making Changes

### 1. Create a branch

```bash
git checkout -b feat/my-feature
```

### 2. Make your changes

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- No wildcard imports
- Thread safety: use `ReentrantReadWriteLock` for shared state, IO dispatcher for inference serialization
- Add tests for new functionality — use the existing mock patterns in `src/test/`

### 3. Add the license header

All `.kt` files must include the Apache 2.0 header:

```kotlin
/*
 * Copyright 2026 T6X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

### 4. Run checks

```bash
./gradlew test        # All 60 tests must pass
./gradlew build       # Clean build
```

### 5. Commit with a conventional message

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(session): add session timeout configuration
fix(download): handle HTTP 416 on resume
docs: add threading model to README
chore(deps): bump work-runtime-ktx to 2.11
```

Scopes: `session`, `download`, `probe`, `registry`, `network`, `docs`, `deps`

### 6. Open a pull request

Push your branch and open a PR against `main`.

## Reporting Issues

- **Bugs**: Open an issue with steps to reproduce, including Android API level and device info
- **Features**: Open an issue describing the use case and proposed API

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Please be respectful and constructive.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
