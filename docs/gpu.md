# Fabric Folia — Experimental GPU Subsystem (OpenCL)

Status: **experimental, strictly opt-in, isolated, measurable, recoverable.**
It is a hand-rolled OpenCL subsystem inside this mod — not a repackaged or
indirectly invoked third-party OpenCL implementation. The mod operates
normally on Windows, macOS, and Linux with GPU acceleration entirely absent.

## What actually executes on the GPU

One real kernel, `aabb_overlap_mask`, mirroring vanilla `AABB#intersects`
for bulk entity-broadphase-style queries: given N boxes as a flat
double array and one query box, one work-item per box writes a 0/1 overlap
mask. The predicate is **pure IEEE-754 comparison, no arithmetic**, so the
GPU result is bit-identical to the CPU reference by construction (and is
proven bit-exact by the verification battery and tests, not assumed).

## Architecture (all in `common`, no Minecraft classes)

| Piece | File | Role |
| --- | --- | --- |
| Contract | `gpu/GpuBackend.java` | overlap-mask query API, `close()` |
| CPU reference | `gpu/CpuBackend.java` | canonical vanilla predicate (the source of truth) |
| OpenCL binding | `gpu/OpenClRuntime.java` | ~20 FFM downcalls (JDK 25 `java.lang.foreign`), discovery, context, kernel lifecycle |
| Verification | `gpu/GpuVerification.java` | boot/test parity battery: edge cases (touching faces, NaN, ±1e308, degenerate) + seeded random batches |
| Lifecycle | `gpu/GpuSubsystem.java` | init/discovery/selection, dispatch, degradation, metrics, `/folia gpu bench` |
| Values | `gpu/GpuDevice.java`, `gpu/GpuException.java` | device description, failure type |

No third-party dependency: OpenCL's ABI is stable C and the ICD loader
(`OpenCL.dll` / `libOpenCL.so.1` / `OpenCL.framework`) ships with GPU
drivers. JDK 25 FFM restricted-method warnings appear once at first use;
add `--enable-native-access=ALL-UNNAMED` to silence them (not required).

## Opt-in and failure handling (by design, not best-effort)

- `gpu.enabled` (default **false**), `gpu.device` (auto/`gpu`/`cpu`/name
  substring), `gpu.verify-on-boot` (default true).
- Boot sequence: discover → select → build kernel → **parity battery must
  pass bit-exactly before the GPU may serve anything**.
- Every failure — missing ICD, no device, no fp64, build error, verification
  mismatch, or a mid-flight enqueue error — degrades to the CPU reference
  with the reason in `/folia metrics` (`GPU: state=... reason=...`) and the
  counters `errors`/`fallbacks`. Queries never fail because of the GPU.
- Runtime degradation is one-way for the session (CPU reference after the
  first mid-flight error); startup can be retried by restarting.

## Measured results (this repo's test host: Windows 11, JDK 25.0.4, NVIDIA GeForce RTX 5060 Ti, OpenCL 3.0 CUDA, 36 CUs, fp64)

- Boot parity battery: **passed** (`/folia gpu` shows `Boot parity verified: true`).
- `GpuParityTest` (JUnit, live device): 8 seeded random batches up to
  30 000 boxes + extreme-magnitude batch — **bit-exact** on every round.
- `/folia gpu bench` (live server, parity EXACT at every size):

| boxes | CPU | GPU | winner |
| --- | --- | --- | --- |
| 200 000 | 1.24 ms (161 668 boxes/ms) | 9.34 ms (21 422 boxes/ms) | CPU ~7.5× |
| 1 000 000 | 3.95 ms (253 428 boxes/ms) | 40.07 ms (24 957 boxes/ms) | CPU ~10× |
| 4 000 000 | 11.97 ms (334 149 boxes/ms) | 142.90 ms (27 992 boxes/ms) | CPU ~12× |

**Honest conclusion:** for this kernel shape the GPU loses at every measured
batch size. The CPU reference is a tight, cache-friendly double loop, while
each GPU dispatch pays a full round trip (buffer create + host→device copy +
enqueue + finish + device→host copy). The GPU ceiling observed is
~28 000 boxes/ms versus ~334 000 boxes/ms on the CPU. GPU acceleration for
this workload would only be justified after: persistent device buffers
(no per-call allocation/copy), many queries per uploaded batch, or kernels
heavier than six comparisons per box. Until then the CPU reference is the
serving path even when the GPU is ACTIVE — nothing routes gameplay through
the GPU in this release.

## OS / device coverage (what was actually exercised)

| Environment | Status | Evidence |
| --- | --- | --- |
| Windows 11 + NVIDIA (RTX 5060 Ti) | **verified live** | boot battery, JUnit parity, `/folia gpu bench`, clean shutdown |
| Windows/Linux/macOS without OpenCL | verified by design + CI | discovery failure → CPU fallback with reason; `GpuParityTest` self-skips via JUnit assumption (skips≠failures) |
| Linux + NVIDIA/AMD/Intel OpenCL | unverified on this project | kernel is standard OpenCL C 1.2 + fp64; needs a labelled CI agent with a device |
| macOS (Apple Silicon, Metal-backed OpenCL 1.1/2.0) | unverified on this project | fp64 via `cl_khr_fp64` is expected present; battery would prove it on first run |

## The trap future kernel work must not re-learn

`clCreateContext`'s `devices` parameter (and `clBuildProgram`'s
`device_list`) are **pointers to an ARRAY of handles**, not a handle. Passing
the raw handle makes the ICD dereference driver memory as a device pointer;
with NULL properties (platform derived from the device) this crashed the
JVM with `EXCEPTION_ACCESS_VIOLATION` inside `nvopencl64.dll`. The fix is
one slot holding the handle (see the why-comment in `OpenClRuntime.open()`).

## Threading / lifecycle contract

- `GpuSubsystem.initialize`/`close` are lifecycle-singleton operations
  (startup/shutdown); the dispatch hot path reads one volatile backend
  reference plus LongAdders and never takes the lifecycle lock.
- Dispatches are serialized per backend instance (kernel args are per-kernel
  state); callers get plain `int[]` results with no shared mutable output.
- Close-during-dispatch is safe: in-flight calls fail with
  `CL_INVALID_COMMAND_QUEUE`, the degradation path serves the CPU result.

## Commands

- `/folia gpu` — state, backend, devices, boot-verification flag, counters.
- `/folia gpu bench [n]` — CPU vs GPU throughput on seeded data + parity verdict.
- `/folia metrics` — `GPU: state=...`, `GPU: invocations=... errors=... fallbacks=... gpuMs=... cpuMs=...`.
