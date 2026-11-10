# Memory Diagnostics Utilities for Spring Boot

A secure, performance-optimized micro-utility package designed to trigger JVM Garbage Collection, generate native HotSpot heap dumps, and stream large diagnostic artifacts over HTTP channels safely.

---

## Key Features

* 🚀 **Zero-Footprint Heap Dump Generation**: Uses Java reflection to interface directly with `com.sun.management.HotSpotDiagnosticMXBean` without external dependencies.
* 📦 **Low-Memory Chunked Streaming**: Implements Spring `StreamingResponseBody` to stream multi-gigabyte `.hprof` files in **8KB fragments**, keeping the host JVM memory footprint entirely flat.
* 🔒 **Directory Traversal Mitigation**: Uses path normalization to isolate all file activities inside a designated sandboxed environment.
* 🛡️ **Extension Lock Down**: Hard-coded constraints reject any structural deletions on non-diagnostic files (strictly enforces `.hprof`).

---

## File Architecture

```text
src/main/java/com/example/demo/
│
├── controller/
│   └── DiagnosticsController.java    # Exposes REST endpoints for GC, dumping, listing & purging
│
└── util/
    └── PathSecurityUtil.java         # Sandbox path enforcement engine
```

---

## Security Framework Breakdown

The application maintains safety against remote directory path injection via `PathSecurityUtil.java`:
1. **Canonicalization**: Every file instruction calls `.toAbsolutePath().normalize()` to strip relative path injection parameters like `./` or `../`.
2. **Sandbox Validation**: System verification ensures every calculated absolute path structurally uses the prefix matching the default root target volume (e.g., `/tmp`).

---

## Endpoints Guide

### 1. Trigger Garbage Collection
Triggers a manual runtime Garbage Collection phase.
* **URL**: `/api/diagnostics/gc`
* **Method**: `POST`
* **Sample Curl**:
  ```bash
  curl -X POST http://localhost:8080/api/diagnostics/gc
  ```
* **Success Response (`200 OK`)**:
  ```text
  Garbage collection triggered successfully.
  ```

### 2. Generate Heap Dump
Generates an `.hprof` log trace to disk.
* **URL**: `/api/diagnostics/heapdump`
* **Method**: `POST`
* **Optional Query Parameters**:
  * `dir` (Default: `/tmp`): Target folder inside the sandbox.
* **Sample Curl**:
  ```bash
  curl -X POST "http://localhost:8080/api/diagnostics/heapdump?dir=/tmp"
  ```
* **Success Response (`200 OK`)**:
  ```text
  Heap dump generated successfully at: /tmp/heapdump-1783749201923.hprof
  ```

### 3. List Diagnostic Logs
Lists available files inside the sandbox without loading sub-directory trees.
* **URL**: `/api/diagnostics/files`
* **Method**: `GET`
* **Optional Query Parameters**:
  * `dir` (Default: `/tmp`): Target directory to query.
  * `hprofOnly` (Default: `true`): Filters listing down to `.hprof` items.
* **Sample Curl**:
  ```bash
  curl -X GET "http://localhost:8080/api/diagnostics/files?hprofOnly=true"
  ```
* **Success Response (`200 OK`)**:
  ```json
  [
    {
      "name": "heapdump-1783749201923.hprof",
      "absolutePath": "/tmp/heapdump-1783749201923.hprof",
      "sizeBytes": 1073741824,
      "lastModified": 1783749201000
    }
  ]
  ```

### 4. Download Heap Dump File
Streams large binary files over chunked network channels.
* **URL**: `/api/diagnostics/heapdump/download`
* **Method**: `GET`
* **Required Query Parameters**:
  * `path`: Absolute location of the file to download.
* **Sample Curl**:
  ```bash
  curl -O -J "http://localhost:8080/api/diagnostics/heapdump/download?path=/tmp/heapdump-1783749201923.hprof"
  ```

### 5. Secure Delete File
Removes file logs safely from disk.
* **URL**: `/api/diagnostics/heapdump`
* **Method**: `DELETE`
* **Required Query Parameters**:
  * `path`: Absolute location of the target file to be purged.
* **Sample Curl**:
  ```bash
  curl -X DELETE "http://localhost:8080/api/diagnostics/heapdump?path=/tmp/heapdump-1783749201923.hprof"
  ```
* **Success Response (`200 OK`)**:
  ```text
  File deleted successfully: heapdump-1783749201923.hprof
  ```

---

## Security Error Response Codes

* **`403 Forbidden`**: Triggered when a path traversal input attempts to break past the sandbox container boundaries.
  ```text
  Access Denied: Path is outside the permitted directory structure.
  ```
* **`400 Bad Request`**: Triggered if a deletion instruction points to a valid file inside the sandbox that does not match the `.hprof` file extension extension restriction rules.
  ```text
  Invalid Request: Only .hprof configurations can be dropped from memory diagnostics.
  ```
* **`404 Not Found`**: Triggered if a path points to a file or folder that does not exist on the target system filesystem.
