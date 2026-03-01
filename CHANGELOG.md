# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — Unreleased

### Added

- `ModelRegistry` — thread-safe model descriptor store with `ReentrantReadWriteLock`
- `ModelStateStore` — thread-safe status tracking with change callbacks
- `SessionManager` — LRU session cache with priority-aware eviction and reference counting
- `DownloadManager` — streaming downloads with SHA256 verification, disk space checks, and progress throttling
- `HttpDownloadDataSource` — chunked HTTP download with connection pooling and cancellation
- `ModelDownloadWorker` — WorkManager-based background downloads with HTTP resume (Range header)
- `WorkManagerDownloadCoordinator` — bridges `DownloadManager` and WorkManager with network constraints
- `AcceleratorProbe` — hardware compatibility probing for NNAPI, GPU, and CPU
- `DeviceBenchmark` — device performance classification (FAST / MID / SLOW)
- `NetworkPolicyProvider` — metered / WiFi-only network policy enforcement
- `PostDownloadOrchestrator` — automatic probe and benchmark after model download
- `DustCoreErrorExtensions` — `toMap()` extension for error serialization
- 60 JUnit tests across 4 test suites (sessions, downloads, probing, registry)
