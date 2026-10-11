# MW2Recomp — Code Audit Summary

**Date**: 2026-10-11
**Auditor**: Solar (assisted by read-only inspection of repo snapshot)
**Scope**: Runtime correctness, stability, and performance on Mali-G68 MC4 (Dimensity 920-style TBDR)
**Method**: Static review of the same Vulkan/MALI-relevant source files the previous discussion centered on, plus docs and build surfaces. Not a dynamic test run.

## Headline judgement

The project is well engineered for a ahead-of-time PPC->host recompiler with a "bring your own disc/files" distribution model. The GPU layer is unusually careful: fixed pipeline layout, explicit synchronization design, render-pass/tile-awareness documented in code, and a shader-warmup system aimed squarely at the Mali compile-time pathology described in `docs/shaders.md`.

That said, **for your specific symptoms (16 FPS + 19-minute crash on Mali-G68)**, the code shows several plausible failure modes that are worth treating as high priority. None of them is a proven root cause from this audit alone, but together they are consistent with the behavior you described.

---

## 1. High-value strengths

1. **Shader pipeline architecture is on the right track**
   - Separate shader library precompilation + fast linking + background optimized-linking.
   - Background warmup queue with backpressure against render submits.
   - `Skip("async pipeline compile")`-style path on Android so a pipeline miss can be non-blocking.

2. **Mali compile-time pathology is explicitly known and addressed**
   - `docs/shaders.md` documents the Mali driver compile-time problem with the state-machine `OpSwitch` lowering and the forward-jump structured-control-flow fix.
   - The codebase already has a structured-control-flow path in `shader_translator` work (from prior context), which is the right direction.

3. **Explicit synchronization design**
   - Frame-slot fence model, `RetireBeforeDestroy`, segment/cmd-buffer separation, and careful ordering of uploads vs draws.
   - This is the right architecture for avoiding use-after-free and GPU-CPU races.

4. **Texture cache has real eviction + budget logic**
   - `MW2_TEXTURE_BUDGET_MB`, `OnTrimMemory` handling in `runtime/android/perf.cpp`, eviction after 10 seconds idle, descriptor-set lifetime tracking.
   - That matters a lot on a 4-core Mali with limited device-local memory.

5. **Documentation is unusually good for this kind of project**
   - `docs/shaders.md`, `docs/textures.md`, `docs/rendering.md` explain the hard parts (EDRAM emulation, resolve model, shader loading, control-flow translation). This is a major maintainability plus.

---

## 2. Why 16 FPS is plausible even with a "good" architecture

16 FPS = ~62 ms/frame. On a TBDR mobile GPU, that is usually not "GPU too slow to render 60 FPS of geometry." It is more often:

- **per-frame CPU serialization**, frequently shader/pipeline compilation or descriptor/set churn,
- **tile-breaking**, where the frame is split into many small render passes instead of one wide tile-optimized pass,
- or **GPU-CPU desync** where the CPU waits on fence/queue too often.

The code has several places where each of those can creep in.

---

## 3. Most likely technical suspects for your symptoms

### A. Per-frame pipeline/shader compilation still possible on the draw path
`runtime/gpu/vulkan/renderer_draw.cpp:setupFrom` and `stateFrom` timing, plus `EnsurePipeline()` in `runtime/gpu/vulkan/renderer_pipelines.cpp`, show that pipeline work is timed and that the Android path at least attempts to enqueue misses instead of blocking.

But:
- If the background warmup cannot keep up with the rate new shader/pipeline combinations appear, the draw path can still hit `FindPipeline()` miss behavior during gameplay.
- On Mali, even "fast linked" pipelines can be expensive if the driver is still doing heavy work on first use.
- If the forward-jump structured lowering is not yet covering all branched shaders, the Mali compile-time pathology can still fire for the few remaining shaders.

**Relevance to you**: consistent 16 FPS suggests something is limiting every frame, not just occasional spikes. Pipeline/shader availability is a prime candidate.

### B. Mali TBDR vs. frequent render-pass breaks
`renderer_targets.cpp:BeginRendering()` and `renderer_frame.cpp:EndPass()` show that the renderer deliberately opens and closes passes per target change, per resolve read, per clear idiom, etc.

That is correct Vulkan. But on a TBDR device:
- Each `vkCmdBeginRenderPass` / `vkCmdEndRenderPass` (or dynamic-rendering equivalent) can become expensive if it happens very often.
- The clear-idiom handling and multisampled resolve paths add more pass-boundary work.

If a frame is being chopped into many passes, Mali often loses badly compared with immediate-mode desktop GPUs.

**Relevance to you**: Mali-G68 is exactly the kind of GPU where render-pass granularity can dominate frame time. If the world pass is being broken up, 62 ms/frame is a believable outcome.

### C. Texture budget vs. Mali device-local memory pressure
`runtime/gpu/vulkan/texture_cache.cpp` sets the default budget to **half the device-local memory, clamped to 256 MB..2048 MB**. On a Mali-G68 with modest local memory, that can still be a lot, especially with:
- many resolved copies,
- multisampled twins,
- per-frame resolve ring copies,
- EDRAM-emulated targets at scale.

`runtime/android/perf.cpp` does respond to `onTrimMemory`, but that is reactive. If the device is already near its comfortable limit before the OS signals trim, the runtime may spend time in eviction, staging, and device memory fragmentation before it ever sees a trim signal.

**Relevance to you**: a 19-minute crash can be memory pressure accumulation, driver-side OOM, or a driver giving up after sustained pressure. Mali drivers historically do not always fail cleanly.

### D. Long-running accumulation / leak-like behavior
There is no obvious obvious "leak" in the shutdown path; `renderer.cpp`, `renderer_pipelines.cpp`, and `texture_cache.cpp` all have fairly complete cleanup. But long runs can still drift through:
- pipeline/table growth,
- cached target/pass/framebuffer growth in legacy mode,
- descriptor-set or sampler retention patterns,
- resolve-destination image growth per address/surface,
- warm-cache rebuild behavior across sessions.

If the runtime is creating more Vulkan objects per run than it reclaims promptly, a 19-minute crash is consistent with a device-side limit being reached.

### E. Command stream parsing / PM4 edge cases
`command_processor.cpp` is where guest GPU commands become host Vulkan work. That file is large and central. On Mali, malformed or unusual packet sequences are more likely than on desktop drivers to cause:
- stalls,
- driver resets,
- or crashes that look like "the game just died after a while."

If a particular level, effect, or resolution-scaling path triggers an unusual packet pattern, the crash may only appear after enough frames have exercised it.

### F. The "408 shaders" detail
From prior context, "408 shaders" was likely a count marker, not shader #408 being special. Still worth confirming:
- Is there a dynamic counter visible in logs around the crash?
- Does the crash correlate with a particular draw/pass/resolve pattern, or with a warmup-completion milestone?

---

## 4. Android / driver loading notes

- `runtime/gpu/vulkan/loader.cpp` does dynamic `vkGetInstanceProcAddr` / `vkGetDeviceProcAddr` loading and logs missing entry points. That is good for portability.
- `runtime/android/` has window, input, audio, perf, log, driver, and JNI glue.
- The Android path matters a lot here because Mali behavior often depends on the actual vendor driver in the device, not just the GPU model.

If you are using a custom driver package (for example a Mesa/Turnip-like import), that can change the symptoms substantially versus stock vendor driver.

---

## 5. What would be most useful to see next

To move from "plausible" to "likely," the audit needs runtime evidence from your device. The highest-value traces are:

1. **Logcat around the crash**, especially:
   - `renderer:` lines about pipeline creation during gameplay,
   - `warmup:` backoff/compile lines,
   - any Vulkan device-loss or timeout messages,
   - texture eviction/live-bytes lines if present,
   - fence wait timeout lines.

2. **Frame-time breakdown**, ideally from the runtime's own renderer report or a frame profile:
   - how much is draw/setup,
   - how much is submit/fence wait,
   - how much is texture work,
   - whether frame time is flat 62 ms or spiking.

3. **Whether the problem gets worse over repeated runs**, which would point toward accumulation rather than a fixed-cost per-frame bottleneck.

4. **Device/driver details**:
   - exact SoC and vendor driver,
   - whether you are using stock or imported driver,
   - target resolution/scale settings.

---

## 6. Recommended next move

I would treat this as a two-track effort:

1. **Short term**: get a clean reproduce with logs from the crash window. The single most informative thing is the last few seconds before the crash and whether 16 FPS is steady or spiky.

2. **Next engineering step**: once we know whether the bottleneck is shader/pipeline availability, render-pass granularity, texture memory pressure, or command-stream behavior, narrow to the specific file and path. From there, targeted instrumentation or a focused fix is much more likely to help than broad changes.

---

If you want, I can now:
- turn this into a concrete investigation plan for your Mali-G68 run,
- review the next files you paste,
- or go deeper on one suspected subsystem, for example the draw path, warmup path, or texture/eviction path.
