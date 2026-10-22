# EcoArmor PDC performance verification

This fork changes only the ItemStack overloads of `getSetOnItem` and `isAdvanced`.
They read the existing typed PDC markers through Paper's read-only view instead of
constructing complete ItemMeta. ItemMeta overloads and all writes remain unchanged.
There is no cross-call cache, polling change, holder/hash shortcut, or scheduler change.

The original Folia source requires `SchedulerHelper`, which is absent from the public
libreforge Maven dependency. Extract just this compile-time helper from the patched
server dependency's **unshaded** `core/common/build/libs/common-2026.27.jar`
(Python standard library only):

```text
python verification/prepare_scheduler_api.py <unshaded-common.jar> <scheduler-api.jar>
gradlew.bat clean check shadowJar -Plibreforge-api-jar=<absolute-scheduler-api.jar>
```

Do not use a shaded standalone libreforge JAR here: its relocated Kotlin metadata
cannot supply the original Kotlin API, even when only the helper is extracted.
The extracted helper is `compileOnly`; the server still supplies libreforge at runtime.

`EcoArmorProbe.java` is a separate diagnostic plugin, never part of EcoArmor's JAR.
Compile it against the exact isolated core, libraries, eco, libreforge and candidate
EcoArmor. Start that local server with
`-Decoarmor.baseline=<absolute-original-EcoArmor.jar>`. Its child classloader loads only
baseline ArmorUtils classes and delegates other types to the running candidate.
This oracle is for correctness comparisons. Its ArmorSet calls resolve to the
running plugin, so **do not use the oracle for full-path performance claims**.

The regression command is `/ecoarmorprobe regression` from a real test player.
Fixture sets `probe_a`/`probe_b` must cover 4/2-piece full sets, stacked/highest partial
holders and full-set partial suppression on/off. Automatic EcoArmor providers are
unregistered only in this isolated diagnostic process, avoiding duplicate events.
The probe compares marker results/exceptions, read-only item bytes, ordered holders,
live players and armor stands, definition replacement, tier attributes, and deferred
equip/unequip sequences including a same-tick change reverted before delivery.
The supplied `enderite` definition also exercises native full/partial/advanced holders.
The final old/new attribute rounds force effects to refresh, assert +4 max health
for the regular full set, and assert the baseline is restored after unequipping.

`/ecoarmorprobe bench` measures complete `getActiveHolders` calls on a platform thread
using detached real CraftItemStacks and test equipment/entity proxies. It never reads
a live player/inventory from that thread. Install the actual original/candidate JAR
in four separate complete processes in old/new/new/old order, with identical dependencies
and fixtures. Set `-Decoarmor.variant=old` or `new` on each process. Only the running
plugin's implementation is benchmarked; the child-classloader oracle is never timed.
Each fixture has 12,000 warmup calls and 16 samples of 5,000 calls per process.
ThreadMXBean allocation counters must be supported and nonnegative.
`analyze.py <evidence-directory>` validates all four finished logs and computes
per-fixture medians, retaining per-process medians to reveal timing variation.

These measurements are per-call allocation and wall-clock microbenchmarks, not
whole-server CPU, resident heap, production MSPT, or a production saving guarantee.
The original production JAR is the oracle; recompiling the baseline is not a substitute.
