package com.scr01.scroot

import java.util.ArrayDeque

object BootTraceBus {

    enum class RunState {
        IDLE,
        RUNNING,
        SUCCESS,
        FAILURE
    }

    enum class Stage {
        BOOT,
        MEMORY,
        UAF,
        PGD,
        PATCH,
        KSU,
        DONE
    }

    enum class Tone {
        DEFAULT,
        MUTED,
        TELEMETRY,
        PAYLOAD,
        SUCCESS,
        CAUTION,
        WARNING,
        ERROR
    }

    data class Line(
        val text: String,
        val tone: Tone,
        val stage: Stage,
        val countdownDeadlineElapsedMs: Long? = null
    )

    data class Snapshot(
        val generation: Long,
        val startedAtElapsedMs: Long,
        val state: RunState,
        val stage: Stage,
        val detail: String,
        val lines: List<Line>
    )

    interface Listener {
        fun onTraceReset(snapshot: Snapshot)
        fun onTraceLine(line: Line)
        fun onTraceFinished(state: RunState, detail: String)
    }

    private const val MAX_LINES = 1_200
    private const val MAX_LINE_CHARS = 240
    private val lock = Any()
    private val lines = ArrayDeque<Line>()
    private val listeners = LinkedHashSet<Listener>()
    private var generation = 0L
    private var startedAtElapsedMs = 0L
    private var state = RunState.IDLE
    private var stage = Stage.BOOT
    private var detail = ""

    fun begin() {
        val callbacks: List<Listener>
        val snapshot: Snapshot
        synchronized(lock) {
            generation += 1
            startedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            state = RunState.RUNNING
            stage = Stage.BOOT
            detail = "Waiting for the device safety gate"
            lines.clear()
            callbacks = listeners.toList()
            snapshot = snapshotLocked()
        }
        callbacks.forEach { listener ->
            try {
                listener.onTraceReset(snapshot)
            } catch (_: RuntimeException) {

            }
        }
    }

    fun emit(raw: String) {
        val rendered = BootTracePresentation.render(raw) ?: return
        val countdownDurationMs = BootTracePresentation.minimumUptimeWaitMs(raw)
        val callbacks: List<Listener>
        val published: Line
        val replacementSnapshot: Snapshot?
        synchronized(lock) {
            if (!acceptsPublishedEvents(state)) return
            if (rendered.stage.ordinal > stage.ordinal) stage = rendered.stage
            published = rendered.copy(
                stage = stage,
                countdownDeadlineElapsedMs = countdownDurationMs?.let {
                    android.os.SystemClock.elapsedRealtime() + it
                }
            )
            val replacedTransientLines = if (BootTracePresentation.isAllocatorReady(published)) {
                val updated = collapseAllocatorTransients(lines.toList(), published)
                while (updated.size > MAX_LINES) updated.removeAt(0)
                lines.clear()
                updated.forEach(lines::addLast)
                true
            } else {
                while (lines.size >= MAX_LINES) lines.removeFirst()
                lines.addLast(published)
                false
            }
            detail = BootTracePresentation.detailFor(raw, detail)
            callbacks = listeners.toList()
            replacementSnapshot = if (replacedTransientLines) snapshotLocked() else null
        }
        callbacks.forEach { listener ->
            try {
                if (replacementSnapshot != null) {
                    listener.onTraceReset(replacementSnapshot)
                } else {
                    listener.onTraceLine(published)
                }
            } catch (_: RuntimeException) {

            }
        }
    }

    fun complete(success: Boolean, completionDetail: String) {
        val callbacks: List<Listener>
        val terminalState = if (success) RunState.SUCCESS else RunState.FAILURE
        synchronized(lock) {
            if (!acceptsPublishedEvents(state)) return
            state = terminalState
            if (success) stage = Stage.DONE
            detail = completionDetail
            callbacks = listeners.toList()
        }
        callbacks.forEach { listener ->
            try {
                listener.onTraceFinished(terminalState, completionDetail)
            } catch (_: RuntimeException) {

            }
        }
    }

    fun register(listener: Listener): Snapshot = synchronized(lock) {
        listeners.add(listener)
        snapshotLocked()
    }

    fun unregister(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun snapshotLocked(): Snapshot = Snapshot(
        generation = generation,
        startedAtElapsedMs = startedAtElapsedMs,
        state = state,
        stage = stage,
        detail = detail,
        lines = lines.toList()
    )

    internal fun trimLine(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else {
            line.take(MAX_LINE_CHARS - 1) + "…"
        }

    internal fun acceptsPublishedEvents(currentState: RunState): Boolean =
        currentState == RunState.RUNNING

    internal fun collapseAllocatorTransients(
        existing: List<Line>,
        ready: Line
    ): ArrayList<Line> {
        val updated = ArrayList<Line>(existing.size + 1)
        var readyPlaced = false
        existing.forEach { line ->
            when {
                BootTracePresentation.isAllocatorCaution(line) -> {
                    if (!readyPlaced) {
                        updated.add(ready)
                        readyPlaced = true
                    }
                }
                line.countdownDeadlineElapsedMs != null -> {
                    updated.add(BootTracePresentation.completedMinimumUptime(line))
                }
                else -> updated.add(line)
            }
        }
        if (!readyPlaced) updated.add(ready)
        return updated
    }
}

object BootTracePresentation {

    private const val MAX_INPUT_CHARS = 4_096
    private const val MAX_COUNTDOWN_MS = 10L * 60L * 1_000L
    private const val ALLOCATOR_CAUTION = "[WAIT] Please wait. Do not touch the screen."
    private const val ALLOCATOR_READY = "[READY] Memory stability confirmed"
    internal const val MINIMUM_UPTIME_PREFIX = "[WAIT] "
    internal const val MINIMUM_UPTIME_COMPLETE = "[wait] minimum uptime complete"

    private val memoryField = Regex("""\b(free|available|cached)=(\d+)KiB""")
    private val stableField = Regex("""\bstable=(\d+/\d+)""")
    private val minimumUptimeWait = Regex(
        """\bwaiting\s+([0-9]+(?:\.[0-9]+)?)s\s+for\s+minimum\s+uptime\b""",
        RegexOption.IGNORE_CASE
    )

    private val hiddenPrefixes = listOf(
        "[target]",
        "expected:",
        "cgroup:",
        "[SESSION]",
        "[DEVICE]",
        "[BUILD]",
        "[bringup ",
        "ROOT_ENV ",
        "INSMOD_RC=",
        "EARLY_RESTORE_RC=",
        "PRE_CROWN_RC=",
        "KSUD_RC=",
        "BRINGUP ",
        "INSTALL_RC="
    )

    fun render(raw: String): BootTraceBus.Line? {
        val boundedRaw = bounded(raw)
        var text = boundedRaw
            .replace('\u0000', ' ')
            .trim()
        if (text.isBlank()) return null
        if (hiddenPrefixes.any { text.startsWith(it, ignoreCase = false) }) return null
        if (text.startsWith("+ ") || text == "true") return null

        text = when {
            text == "[START] Automatic boot root started" ->
                "[boot] automatic root sequence started"
            text.startsWith("payload integrity:") ->
                "[verify] ${text.removePrefix("payload integrity:").trim()}"
            text.startsWith("manager preflight:") ->
                "[verify] manager signature preflight"
            text.startsWith("module preflight: clean boot") ->
                "[verify] clean boot · module absent · lock clear"
            text.startsWith("preflight: free=") ->
                compactMemory(text, "boot")
            text.startsWith("preflight: cgroup=") ->
                "[memory] cgroup=${text.substringAfter("cgroup=").trim()}"
            text.contains("waiting") && text.contains("for minimum uptime") ->
                minimumUptimeWaitMs(text)?.let(::minimumUptimeCountdownText)
                    ?: ALLOCATOR_CAUTION
            text == "[CAUTION] allocator stability gate active" ->
                ALLOCATOR_CAUTION
            text == "[READY] allocator stability gate passed" ->
                ALLOCATOR_READY
            text.startsWith("quiet-window:") ->
                compactMemory(text, "wait")
            text.startsWith("allocator stabilization") ->
                "[memory] allocator reclaim precondition"
            text.startsWith("post-precondition: PSI unavailable") ->
                "[memory] PSI unavailable · fallback gate active"
            text.startsWith("post-precondition:") ->
                compactMemory(text, "ready")
            text.startsWith("[psi] reader=SELinux-restricted") ->
                "[psi] restricted · ActivityManager fallback"
            text.startsWith("[memory]") && memoryField.containsMatchIn(text) ->
                compactMemory(text, memoryLabel(text))
            text.startsWith("executing /dev/mali0 exploit") ->
                "[mali] launching CVE-2022-38181"
            text.startsWith("pausing screen and file writes") ->
                "[mali] entering allocator-critical section"
            text.contains("device-side trace (buffered)") ->
                "── device exploit trace ──"
            text.startsWith("app-domain bootstrap:") ->
                "[ksu] loading runtime kernel module"
            text.startsWith("bootstrap exit=") ->
                "[ksu] $text"
            text.startsWith("bootstrap module=") ->
                "[ksu] $text"
            text.startsWith("manager verify:") ->
                if (text.contains("Signature matched", ignoreCase = true)) {
                    "[ksu] manager signature verified"
                } else {
                    "[ksu] $text"
                }
            text.startsWith("CROWN_RC=") && text.contains("RESTORE_RC=0") ->
                "[ksu] manager crown committed"
            text.startsWith("[OK] complete") ->
                "[done] root, KernelSU and system UI are ready"
            else -> text
        }

        val stage = stageFor(boundedRaw)
        val tone = toneFor(text)
        return BootTraceBus.Line(
            text = BootTraceBus.trimLine(text),
            tone = tone,
            stage = stage
        )
    }

    private fun compactMemory(text: String, label: String): String {
        val values = memoryField.findAll(text).associate { match ->
            match.groupValues[1] to match.groupValues[2].toLong()
        }
        fun mib(name: String): Long? = values[name]?.let { (it + 512L) / 1024L }

        return buildString {
            append("[memory] ").append(label)
            mib("free")?.let { append(" free=").append(it).append('M') }
            mib("available")?.let { append(" avail=").append(it).append('M') }
            mib("cached")?.let { append(" cache=").append(it).append('M') }
            stableField.find(text)?.groupValues?.get(1)?.let {
                append(" stable=").append(it)
            }
        }
    }

    private fun memoryLabel(text: String): String = when {
        text.startsWith("[memory] early-boot#") ->
            "wait#${text.substringAfter("early-boot#").substringBefore(' ')}"
        text.startsWith("[memory] post-precondition#") ->
            "ready#${text.substringAfter("post-precondition#").substringBefore(' ')}"
        text.startsWith("[memory] process-start") -> "native"
        text.startsWith("[memory] pre-groom") -> "groom"
        text.startsWith("[memory] post-reclaim") -> "reclaim"
        text.startsWith("[memory] post-release") -> "release"
        else -> "state"
    }

    internal fun isAllocatorCaution(line: BootTraceBus.Line): Boolean =
        line.text == ALLOCATOR_CAUTION

    internal fun isAllocatorReady(line: BootTraceBus.Line): Boolean =
        line.text == ALLOCATOR_READY

    internal fun minimumUptimeWaitMs(raw: String): Long? =
        minimumUptimeWait.find(bounded(raw))?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            if (!it.isFinite()) return@let null
            (it * 1_000.0).toLong().coerceIn(0L, MAX_COUNTDOWN_MS)
        }

    internal fun minimumUptimeCountdownText(remainingMs: Long): String =
        if (remainingMs > 0L) {
            val boundedMs = remainingMs.coerceAtMost(MAX_COUNTDOWN_MS)
            val seconds = (boundedMs / 1_000L +
                if (boundedMs % 1_000L == 0L) 0L else 1L).coerceAtLeast(1L)
            "$MINIMUM_UPTIME_PREFIX${seconds}s remaining. Do not touch."
        } else {
            "${MINIMUM_UPTIME_PREFIX}0s · checking memory. Do not touch."
        }

    internal fun completedMinimumUptime(line: BootTraceBus.Line): BootTraceBus.Line =
        line.copy(
            text = MINIMUM_UPTIME_COMPLETE,
            tone = BootTraceBus.Tone.SUCCESS,
            countdownDeadlineElapsedMs = null
        )

    fun stageFor(raw: String): BootTraceBus.Stage {
        val line = bounded(raw).lowercase()
        return when {
            line.contains("automatic setup complete") ||
                line.startsWith("[ok] complete") ->
                BootTraceBus.Stage.DONE
            line.contains("[2/3]") ||
                line.contains("[2.5/3]") ||
                line.contains("[3/3]") ||
                line.contains("root acquired") ||
                line.contains("bootstrap") ||
                line.contains("ksud") ||
                line.contains("manager verify") ||
                line.contains("crown_rc") ->
                BootTraceBus.Stage.KSU
            line.contains("[patch]") ||
                line.contains("[payload]") ||
                line.contains("[broadcast]") ||
                line.contains("defex") ||
                line.contains("hook enforce") ||
                line.contains("write_shellcode") ->
                BootTraceBus.Stage.PATCH
            line.contains("[pgd]") ||
                line.contains("found pgd") ||
                line.contains("[pte]") ||
                line.contains("[reclaim]") ->
                BootTraceBus.Stage.PGD
            line.contains("[uaf]") ||
                line.contains("[alias]") ||
                line.contains("region freed") ||
                line.contains("freed_idx") ||
                line.contains("jit_freed") ->
                BootTraceBus.Stage.UAF
            line.contains("[memory]") ||
                line.contains("[pressure]") ||
                line.contains("[groom]") ||
                line.contains("allocator stabilization") ||
                line.contains("allocator stability gate") ||
                (line.contains("waiting") && line.contains("minimum uptime")) ||
                line.contains("quiet-window") ||
                line.contains("preflight: free") ->
                BootTraceBus.Stage.MEMORY
            else -> BootTraceBus.Stage.BOOT
        }
    }

    fun detailFor(raw: String, previous: String): String {
        val line = bounded(raw).lowercase()
        return when {
            line.contains("[ready] allocator stability gate passed") ->
                "Memory stability confirmed"
            line.contains("waiting") || line.contains("quiet-window") ->
                "Waiting for a stable memory window"
            line.contains("allocator stabilization") ||
                line.contains("[pressure]") ||
                line.contains("[groom]") ->
                "Preparing the Mali allocator"
            line.contains("[uaf]") || line.contains("[alias]") ->
                "Reclaiming the stale Mali allocation"
            line.contains("[pgd]") || line.contains("[pte]") ->
                "Building the physical write primitive"
            line.contains("[patch]") || line.contains("[payload]") ->
                "Applying the verified kernel patch"
            line.contains("[2.5/3]") ->
                "Integrating Apps, Root menu and Recents"
            line.contains("[2/3]") || line.contains("bootstrap") ||
                line.contains("ksud") ->
                "Loading KernelSU Next"
            line.contains("manager verify") || line.contains("crown_rc") ->
                "Verifying the KernelSU Manager"
            line.contains("automatic setup complete") ->
                "Root, KernelSU and system UI are ready"
            else -> previous
        }
    }

    private fun toneFor(text: String): BootTraceBus.Tone {
        val line = text.lowercase()
        return when {
            line.contains("[error]") || line.contains("[failed]") ->
                BootTraceBus.Tone.ERROR
            text == ALLOCATOR_CAUTION ||
                line.startsWith(MINIMUM_UPTIME_PREFIX.lowercase()) ||
                line.contains("[caution]") ->
                BootTraceBus.Tone.CAUTION
            line.contains("[warning]") ||
                line.contains("[blocked]") ->
                BootTraceBus.Tone.WARNING
            line.contains("[ok]") || line.contains("[done]") ||
                line.contains("[ready]") ||
                line.contains("root acquired") ->
                BootTraceBus.Tone.SUCCESS
            line.contains("[payload]") ||
                line.contains("[patch]") ||
                line.contains("[broadcast]") ->
                BootTraceBus.Tone.PAYLOAD
            line.contains("[memory]") ||
                line.contains("[pressure]") ||
                line.contains("[psi]") ||
                line.contains("[vmstat]") ||
                line.contains("[time]") ->
                BootTraceBus.Tone.TELEMETRY
            line.startsWith("──") ||
                line.contains("[verify]") ||
                line.contains("[boot]") ->
                BootTraceBus.Tone.MUTED
            else -> BootTraceBus.Tone.DEFAULT
        }
    }

    private fun bounded(raw: String): String =
        if (raw.length <= MAX_INPUT_CHARS) raw else raw.take(MAX_INPUT_CHARS)
}
