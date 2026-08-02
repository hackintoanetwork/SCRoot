package com.scr01.scroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootTracePresentationTest {

    @Test
    fun hidesTargetAndShellBringupNoise() {
        assertNull(BootTracePresentation.render("[target] fingerprint"))
        assertNull(BootTracePresentation.render("  [bringup 1] + pm path manager"))
        assertNull(BootTracePresentation.render("+ /data/adb/ksud module list"))
    }

    @Test
    fun mapsAllocatorAndPayloadStagesMonotonically() {
        assertEquals(
            BootTraceBus.Stage.MEMORY,
            BootTracePresentation.stageFor("[pressure] dirtying pages")
        )
        assertEquals(
            BootTraceBus.Stage.UAF,
            BootTracePresentation.stageFor("[uaf] freeing stale JIT handle")
        )
        assertEquals(
            BootTraceBus.Stage.PGD,
            BootTracePresentation.stageFor("[pgd] page=22 entries=240")
        )
        assertEquals(
            BootTraceBus.Stage.PATCH,
            BootTracePresentation.stageFor("[payload] DEFEX byte")
        )
        assertEquals(
            BootTraceBus.Stage.KSU,
            BootTracePresentation.stageFor("[OK] root acquired")
        )
    }

    @Test
    fun nativeTraceKeepsAddressesWithoutAddingTimestamps() {
        val line = BootTracePresentation.render(
            "[pte] alias=0x71aa02f000 slot=384 entry=0x4045c9443"
        )
        requireNotNull(line)
        assertEquals(BootTraceBus.Stage.PGD, line.stage)
        assertTrue(line.text.contains("0x71aa02f000"))
        assertTrue(line.text.startsWith("[pte]"))
    }

    @Test
    fun compactsMemoryTelemetryForTheNarrowTraceWithoutLosingSafetyState() {
        assertEquals(
            "[memory] boot free=17M avail=1030M cache=1168M",
            BootTracePresentation.render(
                "preflight: free=16920KiB available=1054320KiB cached=1196520KiB uptime=45.9s"
            )?.text
        )
        assertEquals(
            "[memory] wait#1 free=40M avail=1158M stable=1/2",
            BootTracePresentation.render(
                "[memory] early-boot#1 available=1186108KiB free=40856KiB " +
                    "threshold=221184KiB low=false stable=1/2"
            )?.text
        )
        assertEquals(
            "[verify] clean boot · module absent · lock clear",
            BootTracePresentation.render(
                "module preflight: clean boot (app procfs restricted, no attempt recorded)"
            )?.text
        )
    }

    @Test
    fun presentationCapsPathologicalLines() {
        val line = BootTracePresentation.render("[payload] " + "A".repeat(1_000_000))
        requireNotNull(line)
        assertEquals(240, line.text.length)
        assertTrue(line.text.endsWith("…"))
    }

    @Test
    fun presentsMinimumUptimeAsACompactTransientSafetyNotice() {
        val wait = BootTracePresentation.render(
            "early-boot: waiting 42.5s for minimum uptime"
        )
        val caution = BootTracePresentation.render(
            "[CAUTION] allocator stability gate active"
        )
        val ready = BootTracePresentation.render(
            "[READY] allocator stability gate passed"
        )

        requireNotNull(wait)
        requireNotNull(caution)
        requireNotNull(ready)
        assertEquals("[WAIT] 43s remaining. Do not touch.", wait.text)
        assertEquals(BootTraceBus.Stage.MEMORY, wait.stage)
        assertEquals(BootTraceBus.Tone.CAUTION, wait.tone)
        assertEquals(
            42_500L,
            BootTracePresentation.minimumUptimeWaitMs(
                "early-boot: waiting 42.5s for minimum uptime"
            )
        )
        assertEquals(
            "[WAIT] 43s remaining. Do not touch.",
            BootTracePresentation.minimumUptimeCountdownText(42_500L)
        )
        assertEquals(
            "[WAIT] 1s remaining. Do not touch.",
            BootTracePresentation.minimumUptimeCountdownText(1L)
        )
        assertEquals(
            "[WAIT] 0s · checking memory. Do not touch.",
            BootTracePresentation.minimumUptimeCountdownText(0L)
        )
        assertEquals("[WAIT] Please wait. Do not touch the screen.", caution.text)
        assertEquals(BootTraceBus.Tone.CAUTION, caution.tone)
        assertTrue(BootTracePresentation.isAllocatorCaution(caution))
        assertEquals("[READY] Memory stability confirmed", ready.text)
        assertEquals(BootTraceBus.Tone.SUCCESS, ready.tone)
        assertTrue(BootTracePresentation.isAllocatorReady(ready))
        assertEquals(
            BootTracePresentation.MINIMUM_UPTIME_COMPLETE,
            BootTracePresentation.completedMinimumUptime(wait).text
        )
        assertEquals(
            null,
            BootTracePresentation.completedMinimumUptime(wait)
                .countdownDeadlineElapsedMs
        )
        assertEquals(
            "Memory stability confirmed",
            BootTracePresentation.detailFor(
                "[READY] allocator stability gate passed",
                "Waiting for a stable memory window"
            )
        )
    }

    @Test
    fun pathologicalCountdownValuesCannotOverflowTheDeadline() {
        assertNull(
            BootTracePresentation.minimumUptimeWaitMs(
                "waiting ${"9".repeat(3_900)}s for minimum uptime"
            )
        )
        assertEquals(
            600_000L,
            BootTracePresentation.minimumUptimeWaitMs(
                "waiting 999999s for minimum uptime"
            )
        )
        assertEquals(
            "[WAIT] 600s remaining. Do not touch.",
            BootTracePresentation.minimumUptimeCountdownText(Long.MAX_VALUE)
        )
    }

    @Test
    fun allocatorReadyCollapsesDuplicateTransientCautions() {
        val wait = requireNotNull(
            BootTracePresentation.render("early-boot: waiting 12s for minimum uptime")
        ).copy(countdownDeadlineElapsedMs = 12_000L)
        val caution = requireNotNull(
            BootTracePresentation.render("[CAUTION] allocator stability gate active")
        )
        val ready = requireNotNull(
            BootTracePresentation.render("[READY] allocator stability gate passed")
        )
        val lines = BootTraceBus.collapseAllocatorTransients(
            listOf(wait, caution, caution),
            ready
        )
        assertEquals(
            1,
            lines.count(BootTracePresentation::isAllocatorReady)
        )
        assertEquals(
            0,
            lines.count(BootTracePresentation::isAllocatorCaution)
        )
        assertTrue(lines.none { it.countdownDeadlineElapsedMs != null })
    }
}
