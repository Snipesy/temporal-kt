# Module core-bridge

This is the module that provides FFM bindings to the Temporal Core Rust library. It also generates the kotlin protobuf
definitions used by the SDK-Core Rust library.

Most (if not all) interop is done against
`rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h` found in
the Temporal SDK-Core submodule (`rust/sdk-core/`).

The SDK-Core submodule is included as a workspace member in our parent Rust workspace (`rust/`). This workspace wraps the
sdk-core crates and maintains its own `Cargo.lock` for reproducible builds (the sdk-core submodule gitignores its lock file
since it's a library). The C-compatible shared library is built as part of the Gradle build for this module.

## Build (Your Platform)

```bash
gradle build
```

## Build (All Platforms)

```bash
gradle cargoBuildAll
gradle copyAllNativeLibs
gradle build
```

## Updating Dependencies

### Cargo.lock (Rust dependency version bumps)

The `Cargo.lock` at `core-bridge/rust/Cargo.lock` is maintained by our parent workspace (not the sdk-core submodule).
This is intentional - sdk-core gitignores its lock file since it's a library, but we need locked dependencies for
reproducible CI builds. The Gradle build uses `--locked` to enforce this.

To update all Rust dependencies to their latest compatible versions:

```bash
cargo update --manifest-path core-bridge/rust/Cargo.toml
```

To update a specific dependency:

```bash
cargo update --manifest-path core-bridge/rust/Cargo.toml -p <package-name>
```

After updating, commit the updated `Cargo.lock`:

```bash
git add core-bridge/rust/Cargo.lock
git commit -m "Update Rust dependencies"
```

### SDK-Core Submodule (upstream changes)

The SDK-Core submodule (`core-bridge/rust/sdk-core`) tracks the SurrealDevelopment fork
[SurrealDevelopment/sdk-rust](https://github.com/SurrealDevelopment/sdk-rust) of
[temporalio/sdk-core](https://github.com/temporalio/sdk-core) (renamed `sdk-rust` upstream).

#### Fork and carried patches

The submodule points at a fork, not upstream, because temporal-kt needs C-bridge functions that upstream does
not provide. Each one lives on a `temporal-kt/*` branch of the fork, based on the upstream commit we ship, and
the submodule pointer references that branch (`branch = ...` in `.gitmodules`). A clone that points at upstream
would fail `git submodule update`, because upstream does not contain these commits.

| Branch | Adds | Why |
|---|---|---|
| `temporal-kt/ephemeral-server-pid` | `temporal_core_ephemeral_server_pid(server) -> u32` | Exposes the OS pid of a dev/test server child process. `EphemeralServers` (core-bridge) records pid + start time per JVM and reaps servers left behind by a JVM that died without closing them. Without the pid the only alternative is guessing from process names, which we refused to do. Upstream PR candidate. |
| `temporal-kt/ephemeral-server-pid` (2nd commit) | No `unwrap()` of the Core worker in any `extern "C"` fn of `worker.rs` | After `temporal_core_worker_finalize_shutdown` the handle holds `None`. A late call (an activity heartbeat racing shutdown was the observed case) used to panic across the FFI boundary and abort the whole host process (JVM SIGABRT). Data-path calls now return `"Worker already shut down"`, control calls become no-ops, and the heartbeat path is additionally wrapped in `catch_unwind`. Upstream PR candidate. |

The Kotlin side binds any fork-only function with a small FFM downcall next to its caller (see
`TemporalCoreEphemeralServer.pid`) rather than adding it to `temporal_sdk_core_c_bridge_h`, so that class keeps
mirroring upstream's header and the fork-only surface stays easy to find and remove.

When a patch is merged upstream, drop the branch: repoint the submodule at the upstream commit that contains the
change (the fork's `main` mirrors upstream) and delete the row above.

**Important:** Our parent workspace (`core-bridge/rust/Cargo.toml`) mirrors the `[workspace.dependencies]` from
sdk-core's Cargo.toml. When updating sdk-core, check if its workspace dependencies changed and sync them if needed.

To update to a newer upstream, rebase the carried branch on the fork first, then move the pointer:

```bash
cd core-bridge/rust/sdk-core
git fetch origin                      # origin = SurrealDevelopment/sdk-rust
git fetch https://github.com/temporalio/sdk-core.git master:upstream-master
git checkout temporal-kt/ephemeral-server-pid
git rebase upstream-master            # re-apply the carried patch; rebuild regenerates the header
git push --force-with-lease origin temporal-kt/ephemeral-server-pid
cd ../../..
```

Then continue with the steps below (they apply to whichever commit the submodule now points at):

```bash
git submodule update --remote core-bridge/rust/sdk-core
# Check sdk-core/rust-toolchain.toml: rustup won't pick it up from our outer workspace cwd,
# so the local toolchain must meet it manually (>= 1.94 as of sdk-core v0.6).
# Check if workspace.dependencies changed in sdk-core/Cargo.toml and sync to rust/Cargo.toml if needed
cargo update --manifest-path core-bridge/rust/Cargo.toml
git add core-bridge/rust/sdk-core core-bridge/rust/Cargo.lock core-bridge/rust/Cargo.toml
git commit -m "Update sdk-core to <commit-sha>"
```

To update to a specific commit or tag:

```bash
cd core-bridge/rust/sdk-core
git fetch origin
git checkout <commit-sha-or-tag>
cd ../../..
# Check if workspace.dependencies changed in sdk-core/Cargo.toml and sync to rust/Cargo.toml if needed
cargo update --manifest-path core-bridge/rust/Cargo.toml
git add core-bridge/rust/sdk-core core-bridge/rust/Cargo.lock core-bridge/rust/Cargo.toml
git commit -m "Update sdk-core to <version>"
```

## Regenerating Bindings

### Kotlin Protobuf Classes (automatic)

Kotlin protobuf classes are auto-generated by Gradle from protos in the sdk-core submodule:
- `crates/protos/protos/api_upstream` - Temporal API protos (from [temporalio/api](https://github.com/temporalio/api))
- `crates/protos/protos/testsrv_upstream` - Test server protos (from sdk-java)
- `crates/protos/protos/local` - Local protos

**No manual regeneration needed** - protos are regenerated automatically during `gradle build`.

### Java FFM Bindings (manual)

The checked-in classes under `src/main/java/io/temporal/sdkbridge/` were originally generated
by JExtract but are now maintained **by hand** (JExtract proved too buggy to keep in the loop).
When the C header changes after an sdk-core update, edit the affected classes directly:

- Struct layout changes MUST be mirrored in the corresponding `$LAYOUT` definitions, including
  explicit `MemoryLayout.paddingLayout(...)` entries so offsets match the C ABI. A stale layout
  does not fail compilation — Rust reads garbage past the end of the Java-allocated struct.
- Diff the header between submodule revisions to find changes:
  `git -C rust/sdk-core diff <old>..<new> -- crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h`
- Enum constants are plain `static int` accessors on `temporal_sdk_core_c_bridge_h`; verify the
  numeric values against the header (cbindgen name changes don't matter, values do).

## JExtract 

JExtract is used to generate Java bindings for the native libraries.
https://jdk.java.net/jextract/

Regeneration is currently a manual process. To regenerate the bindings, run the following command from the root of the repository:

```bash
jextract @includes.txt --output ./src/main/java --target-package io.temporal.sdkbridge ./rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h
```

To regenerate a new includes do

```bash
jextract --dump-includes includes.txt ./rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h
```

Then remove any non-portable files. (i.e. MAC OS / Darwin specific things) which are not important for the bindings.
Commit the results without modification.

Note that in rust many structs are opaque and will generate errors (thats fine)

```
temporal-sdk-core-c-bridge.h:68:16: warning: Skipping TemporalCoreClientGrpcOverrideRequest (type Declared(TemporalCoreClientGrpcOverrideRequest) is not supported)
```
