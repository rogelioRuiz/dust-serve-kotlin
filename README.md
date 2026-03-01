<p align="center">
  <img alt="dust" src="assets/dust_banner.png" width="400">
</p>

# dust-serve-kotlin

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-26+-green.svg)](https://developer.android.com/studio/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org)

Android ML model server library — download management, session caching, accelerator probing, and inference scheduling.

**Version: 0.1.0**

## Overview

`dust-serve-kotlin` is the Android implementation of the [dust-core-kotlin](../dust-core-kotlin) contract interfaces. It provides everything needed to download, verify, cache, and run inference on ML models on Android devices:

- **Download management** with SHA256 verification, resume support, and disk space checks
- **Session caching** with LRU eviction, priority awareness, and reference counting
- **Accelerator probing** for NNAPI, GPU, and CPU with automatic fallback
- **Device benchmarking** to classify hardware performance
- **WorkManager integration** for reliable background downloads
- **Thread-safe** state management with lock separation

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      Consumer (Plugin)                       │
├──────────────┬───────────────────────────┬───────────────────┤
│ ModelRegistry│     SessionManager        │  DownloadManager  │
│ ModelState   │  ┌─────────────────────┐  │  ┌─────────────┐ │
│   Store      │  │  QueuedModelSession │  │  │ HttpDownload │ │
│              │  │  (IO dispatcher,    │  │  │ DataSource   │ │
│              │  │   parallelism=1)    │  │  └─────────────┘ │
│              │  └─────────────────────┘  │  ┌─────────────┐ │
│              │  LRU cache + ref count    │  │ WorkManager  │ │
│              │  Priority-aware eviction  │  │ Coordinator  │ │
├──────────────┴───────────────────────────┴──┴─────────────┴─┤
│  AcceleratorProbe    DeviceBenchmark    NetworkPolicyProvider │
│  PostDownloadOrchestrator                                    │
└──────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|-----------|---------------|
| `ModelRegistry` | Thread-safe model descriptor store (`ReentrantReadWriteLock`) |
| `ModelStateStore` | Status tracking with change callbacks, separate lock from registry |
| `SessionManager` | LRU session cache, priority-aware eviction (BACKGROUND first), concurrent load dedup |
| `DownloadManager` | Streaming downloads with SHA256 digest, progress throttling (512KB intervals), disk space validation |
| `HttpDownloadDataSource` | Chunked HTTP I/O (512KB buffers), connection pooling, cancellation |
| `ModelDownloadWorker` | WorkManager `CoroutineWorker` with HTTP resume (Range header), exponential backoff |
| `WorkManagerDownloadCoordinator` | Bridges `DownloadManager` ↔ `WorkManager`, network constraints |
| `AcceleratorProbe` | Tests NNAPI/GPU/CPU compatibility, caches results per model |
| `DeviceBenchmark` | Single-run median-of-5 benchmark, classifies device as FAST/MID/SLOW |
| `NetworkPolicyProvider` | Enforces WiFi-only or any-network download policy |
| `PostDownloadOrchestrator` | Fires probe + benchmark automatically after model download |

## Install

### Gradle — local project dependency

```groovy
// settings.gradle
include ':dust-serve-kotlin'
project(':dust-serve-kotlin').projectDir = new File('../dust-serve-kotlin')

// Also include the contract library
include ':dust-core-kotlin'
project(':dust-core-kotlin').projectDir = new File('../dust-core-kotlin')

// build.gradle
dependencies {
    implementation project(':dust-serve-kotlin')
}
```

### Gradle — Maven (when published)

```groovy
dependencies {
    implementation 'io.t6x:dust-serve-kotlin:0.1.0'
}
```

## Quick Start

```kotlin
import io.t6x.dust.serve.*
import io.t6x.dust.core.*

// 1. Create core components
val stateStore = ModelStateStore()
val registry = ModelRegistry(stateStore)

// 2. Set up download infrastructure
val downloadManager = DownloadManager(
    dataSource = HttpDownloadDataSource(),
    stateStore = stateStore,
    networkPolicyProvider = SystemNetworkPolicyProvider(context),
    diskSpaceProvider = StatFsDiskSpaceProvider(),
    baseDir = context.filesDir,
    eventEmitter = { event, payload -> Log.d("ModelServer", "$event: $payload") },
)

// 3. Create session manager
val sessionFactory: ModelSessionFactory = MyModelSessionFactory()  // your implementation
val sessionManager = SessionManager(stateStore, sessionFactory)

// 4. Register a model
val descriptor = ModelDescriptor(
    id = "my-model",
    name = "My Model",
    format = ModelFormat.GGUF,
    sizeBytes = 500_000_000L,
    version = "1.0",
    url = "https://example.com/model.gguf",
    sha256 = "abc123...",
)
registry.register(descriptor)

// 5. Download
val job = downloadManager.download(descriptor, coroutineScope)
job.join()

// 6. Load and run inference
val session = sessionManager.loadModel(descriptor, SessionPriority.INTERACTIVE)
val outputs = session.predict(listOf(
    DustInputTensor(name = "input", data = listOf(1.0f, 2.0f), shape = listOf(1, 2))
))

// 7. Release
sessionManager.unloadModel(descriptor.id)
```

## Download Flow

```
NotLoaded ──► Downloading(progress) ──► Verifying ──► Ready
                     │                       │
                     ▼                       ▼
               NotLoaded (cancelled)    Failed(error)
```

- **Progress events** are throttled to 512KB intervals to avoid flooding consumers
- **SHA256 verification** streams through a `MessageDigest` during download (no second pass)
- **Disk space** is validated upfront — requires 2x model size available
- **Part file cleanup** runs on startup to remove stale incomplete downloads
- **HTTP resume** is supported via WorkManager (`Range` header, HTTP 206)

## Threading Model

| Concern | Strategy |
|---------|----------|
| Model descriptors | `ReentrantReadWriteLock` — concurrent reads, exclusive writes |
| Model state | Separate `ReentrantReadWriteLock` — avoids contention with descriptor lock |
| Inference | `Dispatchers.IO.limitedParallelism(1)` — serializes all predictions for thread safety |
| Session loading | `ReentrantLock` + CAS pattern — concurrent `loadModel()` calls produce a single session |
| Downloads | `ConcurrentHashMap` — concurrent download requests for the same model are deduplicated |
| Memory eviction | Lock-then-close pattern — eviction decision under lock, session close outside lock |

## Memory Management

`SessionManager` supports pressure-aware eviction:

```kotlin
// Standard pressure: evict unreferenced BACKGROUND sessions (LRU order)
sessionManager.evictUnderPressure(MemoryPressureLevel.STANDARD)

// Critical pressure: evict all unreferenced sessions (LRU order)
sessionManager.evictUnderPressure(MemoryPressureLevel.CRITICAL)
```

Sessions with active references (`refCount > 0`) are never evicted.

## Test

```bash
./gradlew test    # 60 JUnit tests (4 suites)
```

| Suite | Tests | Coverage |
|-------|-------|----------|
| `SessionManagerTest` | 21 | Load/unload, caching, ref counting, 20-thread concurrency, LRU eviction |
| `DownloadManagerTest` | 15 | SHA256, disk space, progress throttling, concurrent dedup, cancel, retry |
| `AcceleratorProbeAndBenchmarkTest` | 14 | Accelerator selection, output tolerance, caching, device tier classification |
| `ModelServerRegistryTest` | 10 | Register/resolve, 100-thread concurrency, state independence |

No emulator needed — all tests run on the JVM with mocks.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding conventions, and PR guidelines.

## License

Copyright 2026 T6X. Licensed under the [Apache License 2.0](LICENSE).
