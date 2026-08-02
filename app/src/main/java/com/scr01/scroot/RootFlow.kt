package com.scr01.scroot

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object RootFlow {

    const val MANAGER_PKG = "com.rifsxd.ksunext"
    private const val EXPECTED_MANAGER_SIGNER_SHA256 =
        "fbf17c01cd2051f94b72ee65b90f49bfd1920f10a6641c45cc3faeebe1371f7c"
    const val EXPECTED_FINGERPRINT =
        "KDDI/SCR01_jp_kdi/SCR01:11/RP1A.200720.012/SCR01KDU1AVK2:user/release-keys"
    const val EXPECTED_KERNEL_RELEASE = "4.14.186-24165939"
    private const val EXPECTED_MANUFACTURER = "samsung"
    private const val EXPECTED_MODEL = "SCR01"
    private const val EXPECTED_DEVICE = "SCR01"
    private const val EXPECTED_PRODUCT = "SCR01_jp_kdi"
    private const val EXPECTED_BUILD_ID = "RP1A.200720.012"
    private const val EXPECTED_INCREMENTAL = "SCR01KDU1AVK2"
    private const val EXPECTED_SDK = 30
    private const val EXPECTED_PAGE_SIZE = 4096L
    private const val EXPECTED_META_ID = "meta-overlayfs"
    private const val EXPECTED_META_VERSION = "1.3.1-scr01.3"
    private const val META_MODULE_PROP_SHA256 =
        "a5af76462173be19dbf945e1c901f651c8c1794b23b2e78a7b78a42a72612e1c"
    private const val META_BINARY_SHA256 =
        "04ef286eb33dd6650be08c368cc16d06e369aeae2c582f8df520d0a7dcc2fa95"
    private const val META_INSTALL_SHA256 =
        "d37ff93360bb4e88f76fec4d7587b663e312c324727c351bfc715b4d9cfb8aff"
    private const val META_MOUNT_SHA256 =
        "96fa80c084d298cd0707531a07b6920264c5635295ae5d95f34a6ffc7f959254"
    private const val META_POST_MOUNT_SHA256 =
        "cc9e198f97fa4505d5cfc5e04265a8bae9644206576ef73917de95d22d55fce3"
    private const val META_UNINSTALL_SHA256 =
        "0421cf45ab6b1fb51c7c2b1c8a93a29976c967ab8b0bbfadec1a45fbb66475ec"
    private const val META_MODULE_UNINSTALL_SHA256 =
        "cc0e4342a39f8da1dadf5e96f6b0da1884e7bb10de670cbcb74d4bd4ed3e7e49"
    private const val HOME_UI_ASSET = "scr01-home-ui-1.7.27.zip"
    private const val OVERVIEW_UI_ASSET = "scr01-overview-bridge-0.4.36.zip"
    private const val HOME_UI_MODULE_ID = "scr01_scroot_menu"
    private const val HOME_UI_VERSION = "1.7.27"
    private const val HOME_PACKAGE = "com.samsung.android.mhshome"
    private const val HOME_HEALTH_ACTION = "com.scr01.scroot.action.HOME_HEALTH"
    private const val HOME_HEALTH_PROTOCOL = 1
    private const val HOME_HEALTH_RESULT_CODE = 0x5343
    private const val HOME_HEALTH_TIMEOUT_MS = 1_500L
    private const val UI_LIVE_VERIFY_ATTEMPTS = 4
    private const val UI_LIVE_VERIFY_RETRY_MS = 500L
    private const val HOME_HEALTH_NONCE_KEY = "nonce"
    private const val HOME_HEALTH_BUILD_KEY = "build_id"
    private const val HOME_HEALTH_BUILD_ID = "scr01-home-1.7.27"
    private const val OVERVIEW_UI_MODULE_ID = "scr01_overview_bridge"
    private const val OVERVIEW_UI_VERSION = "0.4.36"
    private const val OVERVIEW_PACKAGE = "com.sec.android.app.launcher"
    private const val OVERVIEW_HEALTH_URI =
        "content://com.sec.android.app.launcher.scroot.health"
    private const val OVERVIEW_HEALTH_METHOD = "status"
    private const val OVERVIEW_HEALTH_PROTOCOL = 1
    private const val OVERVIEW_HEALTH_BUILD_KEY = "build_id"
    private const val OVERVIEW_HEALTH_BUILD_ID = "scroverview-0.4.7"
    private const val HOME_UI_PATCHER_SHA256 =
        "50688c893eab8fcf73277590f1b0486f1ba4a63431f568296ddbd864864d3089"
    private const val HOME_UI_DELTA_SHA256 =
        "18fb9cd306d221841c322d29470d4d0a9efd1be984e008e679e4d3868f581cbb"
    private const val HOME_UI_BOOT_SHA256 =
        "39e5f022331e98383db9e06e0e125ea97bff3055136f3c87134ea7f16df2c759"
    private const val HOME_UI_UNINSTALL_SHA256 =
        "de012db3f34aa294b9bad714f4e022fb1fc7395202a507864ea13b8c4b8784ab"
    private const val HOME_UI_MODULE_PROP_SHA256 =
        "a5127520748ce3de48332d0fba449e43c40c84b0cc3a9fac6694ecd97dc98051"
    private const val HOME_UI_SKIP_MOUNT_SHA256 =
        "139d0bf2668b9eb865fcb2f6dd4e8a48a2c379cebee8e87120cfcb0e064f8afb"
    private const val OVERVIEW_UI_PATCHER_SHA256 = HOME_UI_PATCHER_SHA256
    private const val OVERVIEW_UI_DELTA_SHA256 =
        "9d7a2260854ca8f5562e15392a1c3c6939775e8a9c5b2aab6409cb1b6799a5f3"
    private const val OVERVIEW_UI_BRIDGE_SHA256 =
        "e21c90ee1c70c0a3134f532e179bef1cdaaa47105f63fbce4ca850596607258d"
    private const val OVERVIEW_UI_BOOT_SHA256 =
        "a67129119b1bcdc31f3ca6ae71bd366a1db42244aa082e94f98a39d43e2c3f60"
    private const val OVERVIEW_UI_UNINSTALL_SHA256 =
        "efacf50f885aa55428fdbd0bc758019356d673ad8e843603f25e2ecaf5bfc3d4"
    private const val OVERVIEW_UI_MODULE_PROP_SHA256 =
        "be1dd7ed27d90e492330ba266ae7fb272fd938a627b6dedde8e6f3f68a678208"
    private const val OVERVIEW_UI_SKIP_MOUNT_SHA256 =
        "c8215616bd942c95d0f03856107f1861402a601d487444a5fdb1817732a4da5b"
    private const val UI_RECEIPT_FILE = "system-ui-integration.receipt"
    private val UI_STAGE_FILE_PATTERN = Regex(
        "^(scr01-home-ui|scr01-overview-bridge)-[0-9]+\\.[0-9]+\\.[0-9]+\\.zip(?:\\.new)?$"
    )
    private val EXPECTED_ARTIFACT_SHA256 = mapOf(
        "libexploit.so" to "ea4cd0b16bc8c08885b2ab27a92700a0519e619c97026d627b3f74d5dec80a54",
        "librootsh.so" to "77cfc2e1e8fd710cb18b442f950a76b63e0e0f30214c1131aed49d8ff85edc04",
        "libbootstrap.so" to "e81028efeaf44aba81607aa6116cc1273ad8f4d4eee4ec58ff5e14be061fca90",
        "libmemprep.so" to "a743f6c5432ec8072faa1bc47e07d8811135905eb3e458e8f4d892b192cadcee",
        "libksud.so" to "637676421190aeec504093707ad675a45faaf11bd4b53129d52e150490902cca",
        "libksuglue.so" to "1cec66df04a0578e315565658198cf1af26f976cdac11ab3755bb5190d7138da",
        "libksucheck.so" to "0abb36169ff0864ee659e5b40f7666b01cf36d765e3216d9a4d14695f92817d6",
        "libadbroot.so" to "5562adc1e5c6f52fb91f469a1a7d3480050d8697fb5dedbd7fb386d282cd88b8",
        "manager.apk" to "80a9e4b1ba9644f361add3e003e1075bd4f9cb374bbde465c2b57522b5288ba9",
        "meta-overlayfs-scr01.zip" to
            "df4b4a33c9974eb873e62ad01fa7229e9648cc848d032f6192a89b055cb9528c",
        HOME_UI_ASSET to
            "c75717aa479708fd496b3204bad6290f481e61109771cde3ac557f95a0befc23",
        OVERVIEW_UI_ASSET to
            "a3bcae90900fd02436108b8644fb5aa9e247816b98fb8fbe4f0c9ee81ce45981"
    )
    private const val MAX_BOOT_LOG_BYTES = 2L * 1024 * 1024
    private const val MAX_BOOT_LOG_LINE_CHARS = 4 * 1024
    private const val MAX_CAPTURE_LINES = 20_000
    private const val MAX_CAPTURE_CHARS = 4 * 1024 * 1024
    private const val MAX_CAPTURE_LINE_CHARS = 16 * 1024

    private const val MIN_EXPLOIT_UPTIME_S = 70.0
    internal const val MAX_EXPLOIT_UPTIME_S = 240.0
    private const val TARGET_QUIET_UPTIME_S = 120.0
    private const val QUIET_WAIT_EXTENSION_S = 30.0
    private const val POST_PREP_QUIET_TIMEOUT_S = 20.0
    private const val PSI_SAMPLE_MS = 2_000L
    private const val PSI_QUIET_WINDOWS = 2

    private const val MAX_PSI_SOME_STALL_PERCENT = 2.5
    private const val MAX_PSI_FULL_STALL_PERCENT = 0.5
    private const val MIN_AVAILABLE_KB = 750_000L
    private const val FALLBACK_AVAILABLE_HEADROOM_KB = 256L * 1024
    private const val FALLBACK_MAX_DROP_KB = 96L * 1024
    private const val CONDITION_FREE_KB = 400_000L
    private const val HARD_FREE_FLOOR_KB = 80_000L
    private val bootLogLock = Any()
    private val flowRunning = AtomicBoolean(false)
    private val DIAGNOSTIC_KEYS = arrayOf(
        "[init]", "[groom] attempt", "[memory]", "Mali shrinker",
        "region freed", "[spray]", "[alias]", "read 0", "[uaf]",
        "[reclaim]", "jit_freed", "Found freed_idx",
        "[pgd]", "Found pgd", "[patch]", "[pte]", "[1/3]", "[2/3]",
        "[2.5]", "[3/3]", "[slot]", "[fanout]", "[stage]", "[event]", "[abort]",
        "[probe]", "[broadcast]", "[time]",
        "=== ROOT", "uid=", "Killed", "INSMOD_RC=", "LATE_LOAD_RC=",
        "KSUD_RC=", "PRE_CROWN_RC=", "BRINGUP ", "CROWN_RC="
    )
    private val COMPLETE_WRITE_RECEIPT = Regex(
        """^\[broadcast] done defex=[1-9]\d* enforce=[1-9]\d* """ +
            """avc=\{[1-9]\d*,[1-9]\d*,[1-9]\d*\} """ +
            """hooks=\{version:[1-9]\d*,selinux:[1-9]\d*\}$"""
    )

    enum class ExecutionMode {
        MANUAL,
        AUTO
    }

    fun isRunning(): Boolean = flowRunning.get()

    internal enum class ModuleState {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    internal fun moduleStateFromProcText(text: String): ModuleState =
        if (text.lineSequence().any { line ->
                line.substringBefore(' ').trim() == "ksu_glue"
            }) {
            ModuleState.PRESENT
        } else {
            ModuleState.ABSENT
        }

    internal fun moduleStateFromSignals(
        procText: String?,
        sysfsVisible: Boolean
    ): ModuleState = when {
        sysfsVisible -> ModuleState.PRESENT
        procText == null -> ModuleState.UNKNOWN
        else -> moduleStateFromProcText(procText)
    }

    internal fun unknownModuleStateMayStartFresh(
        state: ModuleState,
        exploitRecorded: Boolean
    ): Boolean = state == ModuleState.UNKNOWN && !exploitRecorded

    private fun probeModuleState(): ModuleState {
        if (File("/sys/module/ksu_glue").isDirectory) {
            return ModuleState.PRESENT
        }
        val procText = try {
            File("/proc/modules").readText()
        } catch (_: Exception) {
            null
        }

        return moduleStateFromSignals(
            procText = procText,
            sysfsVisible = false
        )
    }

    fun isModuleLoaded(): Boolean = probeModuleState() == ModuleState.PRESENT

    private fun boundedLogLine(value: String): String =
        if (value.length <= MAX_BOOT_LOG_LINE_CHARS) value else {
            value.take(MAX_BOOT_LOG_LINE_CHARS - 1) + "…"
        }

    private fun rotateBootLog(current: File, previous: File) {
        if (previous.exists() && !previous.delete()) {
            FileOutputStream(current, false).use { }
            return
        }
        if (current.exists() && !current.renameTo(previous)) {
            FileOutputStream(current, false).use { }
        }
    }

    private fun bootLog(ctx: Context, msg: String) {
        synchronized(bootLogLock) {
            try {
                val current = File(ctx.filesDir, "boot.log")
                val encoded = (boundedLogLine(msg) + "\n").toByteArray(Charsets.UTF_8)
                if (current.length() + encoded.size > MAX_BOOT_LOG_BYTES) {
                    rotateBootLog(current, File(ctx.filesDir, "boot.log.1"))
                }
                FileOutputStream(current, true).use { it.write(encoded) }
            } catch (_: Exception) {}
        }
    }

    private fun bootLogBatch(ctx: Context, lines: List<String>, prefix: String = "") {
        synchronized(bootLogLock) {
            try {
                val encodedLines = ArrayDeque<ByteArray>()
                var encodedBytes = 0L
                lines.forEach { line ->
                    val encoded = (prefix + boundedLogLine(line) + "\n")
                        .toByteArray(Charsets.UTF_8)
                    if (encoded.size > MAX_BOOT_LOG_BYTES) return@forEach
                    while (encodedLines.isNotEmpty() &&
                        encodedBytes + encoded.size > MAX_BOOT_LOG_BYTES) {
                        encodedBytes -= encodedLines.removeFirst().size
                    }
                    encodedLines.addLast(encoded)
                    encodedBytes += encoded.size
                }
                val current = File(ctx.filesDir, "boot.log")
                if (current.length() + encodedBytes > MAX_BOOT_LOG_BYTES) {
                    rotateBootLog(current, File(ctx.filesDir, "boot.log.1"))
                }
                FileOutputStream(current, true).use { output ->
                    encodedLines.forEach(output::write)
                }
            } catch (_: Exception) {}
        }
    }

    data class ExecResult(val rc: Int, val lines: List<String>, val timedOut: Boolean)

    private fun diagnosticLine(line: String): Boolean {
        return DIAGNOSTIC_KEYS.any { line.contains(it) }
    }

    internal data class ProcessIdentity(val pid: Int, val startTime: Long)

    internal fun processStartTimeFromStat(stat: String): Long? {
        val commandEnd = stat.lastIndexOf(')')
        if (commandEnd <= 0 || commandEnd + 1 >= stat.length) return null
        val fields = stat.substring(commandEnd + 1)
            .trim()
            .split(Regex("\\s+"))
        return fields.getOrNull(19)?.toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun processIdentity(pid: Int): ProcessIdentity? {
        if (pid <= 1) return null
        val startTime = try {
            File("/proc/$pid/stat").bufferedReader().use { reader ->
                reader.readLine()?.let(::processStartTimeFromStat)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return ProcessIdentity(pid, startTime)
    }

    private fun processTree(rootPid: Int): List<ProcessIdentity> {
        val seen = LinkedHashMap<Int, ProcessIdentity>()
        fun collect(pid: Int) {
            if (pid <= 1 || seen.size >= 128 || seen.containsKey(pid)) return
            val identity = processIdentity(pid) ?: return
            seen[pid] = identity
            try {
                File("/proc/$pid/task/$pid/children")
                    .readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .mapNotNull { it.toIntOrNull() }
                    .forEach(::collect)
            } catch (_: Exception) {}
        }
        collect(rootPid)
        return seen.values.toList().asReversed()
    }

    private fun processPid(process: Process): Int {

        val fromMethod = try {
            val method = process.javaClass.getDeclaredMethod("pid")
            method.isAccessible = true
            (method.invoke(process) as? Number)?.toLong()
        } catch (_: Exception) {
            null
        }
        val fromField = if (fromMethod == null) {
            try {
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                (field.get(process) as? Number)?.toLong()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val parsed = Regex("(?:pid=|Process\\[)(\\d+)")
            .find(process.toString())
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
        val pid = fromMethod ?: fromField ?: parsed ?: return -1
        return pid.takeIf { it in 2..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
    }

    private fun terminateProcessTree(process: Process) {
        val rootPid = processPid(process)
        val identities = if (rootPid > 1) processTree(rootPid) else emptyList()
        var interrupted = false
        identities.forEach { identity ->
            if (processIdentity(identity.pid) == identity) {
                try { Os.kill(identity.pid, OsConstants.SIGTERM) } catch (_: Exception) {}
            }
        }
        try { process.destroy() } catch (_: Exception) {}
        try {
            process.waitFor(750, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        } catch (_: Exception) {
        }
        identities.forEach { identity ->
            if (processIdentity(identity.pid) == identity) {
                try { Os.kill(identity.pid, OsConstants.SIGKILL) } catch (_: Exception) {}
            }
        }
        if (process.isAlive) {
            try { process.destroyForcibly() } catch (_: Exception) {}
            try {
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (_: Exception) {
            }
        }
        try { process.inputStream.close() } catch (_: Exception) {}
        try { process.errorStream.close() } catch (_: Exception) {}
        try { process.outputStream.close() } catch (_: Exception) {}
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal fun consumeBoundedProcessOutput(
        input: InputStream,
        onLine: (String) -> Unit
    ) {
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8 * 1024)
            val line = StringBuilder(MAX_CAPTURE_LINE_CHARS)
            var lineTruncated = false

            fun publishLine() {
                if (line.isNotEmpty() && line[line.length - 1] == '\r') {
                    line.setLength(line.length - 1)
                }
                val value = if (lineTruncated) {
                    line.append('…').toString()
                } else {
                    line.toString()
                }
                onLine(value)
                line.setLength(0)
                lineTruncated = false
            }

            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val value = buffer[index]
                    if (value == '\n') {
                        publishLine()
                    } else if (line.length < MAX_CAPTURE_LINE_CHARS - 1) {
                        line.append(value)
                    } else {
                        lineTruncated = true
                    }
                }
            }
            if (line.isNotEmpty() || lineTruncated) publishLine()
        }
    }

    private fun execBuffered(
        exe: String,
        args: List<String> = emptyList(),
        timeoutMs: Long,
        interruptible: Boolean = true
    ): ExecResult {
        val lines = ArrayList<String>(128)
        val charCount = intArrayOf(0)
        val truncated = AtomicBoolean(false)
        var process: Process? = null
        var outputReader: Thread? = null
        return try {
            val cmd = listOf(exe) + args
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val p = pb.start()
            process = p
            val reader = thread(isDaemon = true, name = "scr01-output") {
                try {
                    consumeBoundedProcessOutput(p.inputStream) { value ->
                        synchronized(lines) {
                            if (lines.size < MAX_CAPTURE_LINES &&
                                charCount[0] + value.length <= MAX_CAPTURE_CHARS) {
                                lines.add(value)
                                charCount[0] += value.length
                            } else if (truncated.compareAndSet(false, true)) {
                                lines.add("! output truncated at safety limit")
                            }
                        }
                        if (diagnosticLine(value)) Log.i("SCR01EXP", value.take(4_000))
                    }
                } catch (_: Exception) {
                }
            }
            outputReader = reader
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var finished = false
            var deferredInterrupt = false
            while (!finished) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    finished = p.waitFor(remaining, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    if (interruptible) throw interrupted
                    deferredInterrupt = true
                }
            }
            if (!finished) {
                terminateProcessTree(p)
            }
            reader.join(2_000)
            if (reader.isAlive) {
                try { p.inputStream.close() } catch (_: Exception) {}
                reader.join(1_000)
            }
            val snapshot = synchronized(lines) { lines.toList() }
            val result = ExecResult(if (p.isAlive) -1 else p.exitValue(), snapshot, !finished)
            if (deferredInterrupt) Thread.currentThread().interrupt()
            result
        } catch (interrupted: InterruptedException) {
            process?.let(::terminateProcessTree)
            try {
                outputReader?.join(1_000)
            } catch (_: InterruptedException) {
            }
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (e: Exception) {
            process?.let(::terminateProcessTree)
            val snapshot = synchronized(lines) {
                lines.add(boundedLogLine("! exec fail: ${e.message}"))
                lines.toList()
            }
            ExecResult(-1, snapshot, false)
        }
    }

    private fun capture(exe: String, vararg args: String, timeoutMs: Long = 30_000): ExecResult =
        execBuffered(exe, args.toList(), timeoutMs)

    internal fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun captureRootScript(
        script: String,
        runTimeoutMs: Long,
        killGraceMs: Long = 5_000L
    ): ExecResult {
        val boundedRunMs = runTimeoutMs.coerceIn(1_000L, 600_000L)
        val boundedGraceMs = killGraceMs.coerceIn(1_000L, 60_000L)
        val runSeconds = (boundedRunMs + 999L) / 1_000L
        val graceSeconds = (boundedGraceMs + 999L) / 1_000L
        val command =
            "exec /system/bin/timeout -k ${graceSeconds}s ${runSeconds}s " +
                "/system/bin/sh -c ${shellSingleQuote(script)}"
        val result = capture(
            "/system/bin/su",
            "-c",
            command,
            timeoutMs = boundedRunMs + boundedGraceMs + 3_000L
        )
        return if (!result.timedOut && (result.rc == 124 || result.rc == 137)) {
            result.copy(timedOut = true)
        } else {
            result
        }
    }

    fun targetMismatch(): String? {
        val kernelRelease = System.getProperty("os.version") ?: ""
        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (_: Exception) {
            -1L
        }
        return when {
            Build.FINGERPRINT != EXPECTED_FINGERPRINT ->
                "firmware fingerprint"
            !Build.MANUFACTURER.equals(EXPECTED_MANUFACTURER, ignoreCase = true) ->
                "manufacturer"
            Build.MODEL != EXPECTED_MODEL || Build.DEVICE != EXPECTED_DEVICE ||
                Build.PRODUCT != EXPECTED_PRODUCT ->
                "model/device/product"
            Build.ID != EXPECTED_BUILD_ID ||
                Build.VERSION.INCREMENTAL != EXPECTED_INCREMENTAL ||
                Build.VERSION.SDK_INT != EXPECTED_SDK ->
                "build/incremental/sdk"
            kernelRelease != EXPECTED_KERNEL_RELEASE ->
                "kernel release"
            Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" ->
                "primary ABI"
            pageSize != EXPECTED_PAGE_SIZE ->
                "page size"
            else -> null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun isRegularFileNoFollow(file: File): Boolean = try {
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    internal fun hasNoSplitApks(splitSourceDirs: Array<String>?): Boolean =
        splitSourceDirs.isNullOrEmpty()

    private fun securePrivateFile(file: File): Boolean {
        if (!isRegularFileNoFollow(file)) return false
        return try {
            Os.chmod(file.absolutePath, 0x180)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyArtifact(file: File, logicalName: String): Boolean =
        try {
            isRegularFileNoFollow(file) && EXPECTED_ARTIFACT_SHA256[logicalName]
                ?.equals(sha256(file), ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }

    private fun rootReady(rootsh: String): Boolean =
        capture(rootsh, "id -u", timeoutMs = 10_000).let { result ->
            !result.timedOut && result.rc == 0 &&
                result.lines.lastOrNull { it.isNotBlank() }?.trim() == "0"
        }

    private fun rootReadyOnCpu(rootsh: String, cpu: Int): Boolean {
        val mask = (1L shl cpu).toString(16)
        return capture(
            "/system/bin/taskset",
            mask,
            rootsh,
            "id -u",
            timeoutMs = 3_000
        ).let { result ->
            !result.timedOut && result.rc == 0 &&
                result.lines.lastOrNull { it.isNotBlank() }?.trim() == "0"
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners
        else signing.signingCertificateHistory
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(ctx: Context, packageName: String): PackageInfo? =
        try {
            ctx.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } catch (_: PackageManager.NameNotFoundException) { null }

    @Suppress("DEPRECATION")
    private fun archiveInfo(ctx: Context, apk: File): PackageInfo? =
        ctx.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        )

    private data class ManagerCheck(val ok: Boolean, val detail: String, val uid: Int = -1)

    private fun verifyManager(ctx: Context, apk: File, requireInstalled: Boolean): ManagerCheck {
        val bundled = archiveInfo(ctx, apk)
            ?: return ManagerCheck(false, "The bundled manager.apk could not be parsed.")
        if (bundled.packageName != MANAGER_PKG) {
            return ManagerCheck(false, "Bundled package name mismatch: ${bundled.packageName}")
        }
        val expectedSigners = signerDigests(bundled)
        if (expectedSigners != setOf(EXPECTED_MANAGER_SIGNER_SHA256)) {
            return ManagerCheck(false, "The bundled manager.apk signer is not trusted.")
        }
        val installed = packageInfo(ctx, MANAGER_PKG)
            ?: return if (requireInstalled) {
                ManagerCheck(false, "KernelSU Manager is not installed.")
            } else {
                ManagerCheck(true, "Pre-install — bundled signature verified")
            }
        if (signerDigests(installed) != expectedSigners) {
            return ManagerCheck(false, "The installed package signature differs from the bundle.")
        }
        val appInfo = try {
            ctx.packageManager.getApplicationInfo(MANAGER_PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) { null }
            ?: return ManagerCheck(false, "The installed manager UID is unavailable.")
        val uid = appInfo.uid
        if (!appInfo.enabled) {
            return ManagerCheck(false, "KernelSU Manager is disabled.", uid)
        }
        if (!hasNoSplitApks(appInfo.splitSourceDirs)) {
            return ManagerCheck(false, "KernelSU Manager contains unexpected split APKs.", uid)
        }
        val expectedApkHash = EXPECTED_ARTIFACT_SHA256["manager.apk"]
        val installedApkMatches = try {
            expectedApkHash != null && isRegularFileNoFollow(File(appInfo.sourceDir)) &&
                expectedApkHash.equals(sha256(File(appInfo.sourceDir)), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
        if (!installedApkMatches) {
            return if (requireInstalled) {
                ManagerCheck(false, "The installed Manager build differs from the bundle.", uid)
            } else {
                ManagerCheck(true, "Bundled signature verified — Manager refresh pending", uid)
            }
        }
        return if (uid >= 0) {
            ManagerCheck(true, "Signature matched; APK matched", uid)
        } else {
            ManagerCheck(false, "The installed manager UID is unavailable.")
        }
    }

    fun isInstalledManagerTrusted(ctx: Context): Boolean {
        val installed = packageInfo(ctx, MANAGER_PKG) ?: return false
        if (signerDigests(installed) != setOf(EXPECTED_MANAGER_SIGNER_SHA256))
            return false
        val appInfo = try {
            ctx.packageManager.getApplicationInfo(MANAGER_PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (!appInfo.enabled) return false
        if (!hasNoSplitApks(appInfo.splitSourceDirs)) return false
        val source = File(appInfo.sourceDir)
        val expected = EXPECTED_ARTIFACT_SHA256["manager.apk"] ?: return false
        return try {
            isRegularFileNoFollow(source) && expected.equals(sha256(source), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun installedPackageHashMatches(
        ctx: Context,
        packageName: String,
        expectedSha256: String
    ): Boolean {
        val appInfo = try {
            ctx.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (!appInfo.enabled) return false
        if (!hasNoSplitApks(appInfo.splitSourceDirs)) return false
        val source = File(appInfo.sourceDir)
        return try {
            isRegularFileNoFollow(source) &&
                expectedSha256.equals(sha256(source), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun stageAsset(ctx: Context, assetName: String): File? {
        val target = File(ctx.filesDir, assetName)
        val temporary = File(ctx.filesDir, "$assetName.new")

        if (verifyArtifact(target, assetName) && securePrivateFile(target)) return target
        return try {
            Files.deleteIfExists(temporary.toPath())
            ctx.assets.open(assetName).use { input ->
                FileOutputStream(temporary, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!verifyArtifact(temporary, assetName)) {
                temporary.delete()
                return null
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            if (securePrivateFile(target) && verifyArtifact(target, assetName)) {
                target
            } else {
                target.delete()
                null
            }
        } catch (_: Exception) {
            temporary.delete()
            null
        }
    }

    internal fun shouldPruneStagedUiFile(name: String): Boolean =
        UI_STAGE_FILE_PATTERN.matches(name) &&
            name != HOME_UI_ASSET && name != OVERVIEW_UI_ASSET

    private fun pruneStagedUiFiles(ctx: Context) {
        try {
            ctx.filesDir.listFiles()?.forEach { candidate ->
                if (candidate.isFile && shouldPruneStagedUiFile(candidate.name)) {
                    candidate.delete()
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun writeSystemUiReceipt(ctx: Context): Boolean {
        val bootId = AutoRootPreferences.currentBootId() ?: return false
        val target = File(ctx.filesDir, UI_RECEIPT_FILE)
        val temporary = File(ctx.filesDir, "$UI_RECEIPT_FILE.new")
        val content = expectedSystemUiReceipt(bootId).joinToString(
            separator = "\n",
            postfix = "\n"
        )
        return try {
            Files.deleteIfExists(temporary.toPath())
            FileOutputStream(temporary, false).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            val expectedBytes = content.toByteArray(Charsets.UTF_8)
            val committed = securePrivateFile(target) &&
                isRegularFileNoFollow(target) &&
                target.length() == expectedBytes.size.toLong() &&
                Files.readAllBytes(target.toPath()).contentEquals(expectedBytes)
            if (!committed) Files.deleteIfExists(target.toPath())
            committed
        } catch (_: Exception) {
            try { Files.deleteIfExists(temporary.toPath()) } catch (_: Exception) {}
            try { Files.deleteIfExists(target.toPath()) } catch (_: Exception) {}
            false
        }
    }

    internal data class SystemUiLiveHealth(
        val overviewReady: Boolean,
        val homeReady: Boolean
    ) {
        val ready: Boolean get() = overviewReady && homeReady
    }

    internal fun provisionFailureMayUseLiveRecovery(rc: Int, timedOut: Boolean): Boolean =
        !timedOut && rc in 57..59

    private fun verifyProvisionedSystemUiLive(ctx: Context): Boolean {
        repeat(UI_LIVE_VERIFY_ATTEMPTS) { attempt ->
            if (probeSystemUiLiveHealth(ctx).ready) return true
            if (attempt + 1 < UI_LIVE_VERIFY_ATTEMPTS) {
                try {
                    Thread.sleep(UI_LIVE_VERIFY_RETRY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun clearSystemUiReceipt(ctx: Context): Boolean {
        val receipt = File(ctx.filesDir, UI_RECEIPT_FILE)
        return try {
            Files.deleteIfExists(receipt.toPath())
            !Files.exists(receipt.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }
    }

    private fun recoverProvisionedSystemUiLive(
        health: SystemUiLiveHealth,
        log: (String) -> Unit
    ): Boolean {
        if (health.ready) return true
        log(
            "  [recovery] live health incomplete " +
                "(overview=${health.overviewReady}, home=${health.homeReady})"
        )
        val script = buildString {
            append("TIMEOUT=/system/bin/timeout\n")
            append("OVERVIEW_ID='$OVERVIEW_UI_MODULE_ID'\n")
            append("HOME_ID='$HOME_UI_MODULE_ID'\n")
            append("OVERVIEW_VERSION='$OVERVIEW_UI_VERSION'\n")
            append("HOME_VERSION='$HOME_UI_VERSION'\n")
            append("OVERVIEW_BOOT_HASH='$OVERVIEW_UI_BOOT_SHA256'\n")
            append("HOME_BOOT_HASH='$HOME_UI_BOOT_SHA256'\n")
            append("module_dir() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$1\"; [ -d \"\$dir\" ] && [ ! -L \"\$dir\" ] && [ -f \"\$dir/module.prop\" ] && [ ! -L \"\$dir/module.prop\" ] && { echo \"\$dir\"; return 0; }; done; return 1; }\n")
            append("file_hash() { [ -f \"\$1\" ] && [ ! -L \"\$1\" ] && \"\$TIMEOUT\" -k 1s 15s sha256sum \"\$1\" 2>/dev/null | awk '{print \$1}'; }\n")
            append("module_not_blocked() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$1\"; for marker in disable remove; do [ ! -e \"\$dir/\$marker\" ] && [ ! -L \"\$dir/\$marker\" ] || return 1; done; done; return 0; }\n")
            append("verify_boot() { id=\$1; version=\$2; expected=\$3; module_not_blocked \"\$id\" || return 1; dir=\$(module_dir \"\$id\") || return 1; grep -qx \"id=\$id\" \"\$dir/module.prop\" && grep -qx \"version=\$version\" \"\$dir/module.prop\" && [ \"\$(file_hash \"\$dir/boot-completed.sh\")\" = \"\$expected\" ] && printf '%s\\n' \"\$dir\"; }\n")
            append("[ -x \"\$TIMEOUT\" ] || exit 70\n")
            if (!health.overviewReady) {
                append("OVERVIEW_DIR=\$(verify_boot \"\$OVERVIEW_ID\" \"\$OVERVIEW_VERSION\" \"\$OVERVIEW_BOOT_HASH\") || exit 71\n")
                append("echo '[ui] recovering native Recents live health'\n")
                append("\"\$TIMEOUT\" -k 30s 110s /system/bin/sh \"\$OVERVIEW_DIR/boot-completed.sh\" || exit 72\n")
                append("tail -n 12 \"\$OVERVIEW_DIR/service.log\" 2>/dev/null\n")
            }
            if (!health.homeReady) {
                append("HOME_DIR=\$(verify_boot \"\$HOME_ID\" \"\$HOME_VERSION\" \"\$HOME_BOOT_HASH\") || exit 73\n")
                append("echo '[ui] recovering Home live health'\n")
                append("\"\$TIMEOUT\" -k 30s 110s /system/bin/sh \"\$HOME_DIR/boot-completed.sh\" || exit 74\n")
                append("tail -n 12 \"\$HOME_DIR/service.log\" 2>/dev/null\n")
            }
            append("echo 'UI_LIVE_RECOVERY_READY'\n")
        }
        val result = captureRootScript(
            script,
            runTimeoutMs = 250_000L,
            killGraceMs = 45_000L
        )
        result.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
        return !result.timedOut && result.rc == 0 &&
            result.lines.any { it == "UI_LIVE_RECOVERY_READY" }
    }

    private fun provisionSystemUi(
        ctx: Context,
        log: (String) -> Unit
    ): Boolean {
        if (!clearSystemUiReceipt(ctx)) {
            log("[ERROR] The previous launcher integration receipt could not be cleared.")
            return false
        }
        val overview = stageAsset(ctx, OVERVIEW_UI_ASSET)
        val home = stageAsset(ctx, HOME_UI_ASSET)
        if (overview == null || home == null ||
            !verifyArtifact(overview, OVERVIEW_UI_ASSET) ||
            !verifyArtifact(home, HOME_UI_ASSET)
        ) {
            log("[ERROR] Launcher and SystemUI payload integrity verification failed.")
            return false
        }
        pruneStagedUiFiles(ctx)

        log("[2.5/3] launcher + sidebar integration")
        log("  payload integrity: launcher modules 2/2")
        val script = buildString {
            append("KSUD=/data/adb/ksud\n")
            append("NSENTER=/system/bin/nsenter\n")
            append("TIMEOUT=/system/bin/timeout\n")
            append("SYNC=/system/bin/sync\n")
            append("FLOCK=/system/bin/flock\n")
            append("OVERVIEW_ZIP='${overview.absolutePath}'\n")
            append("HOME_ZIP='${home.absolutePath}'\n")
            append("OVERVIEW_ID='$OVERVIEW_UI_MODULE_ID'\n")
            append("HOME_ID='$HOME_UI_MODULE_ID'\n")
            append("OVERVIEW_LOCK=/data/adb/.scr01_overview_bridge.activation.lock\n")
            append("HOME_LOCK=/data/adb/.scr01_scroot_menu.activation.lock\n")
            append("OVERVIEW_VERSION='$OVERVIEW_UI_VERSION'\n")
            append("HOME_VERSION='$HOME_UI_VERSION'\n")
            append("OVERVIEW_ZIP_HASH='${EXPECTED_ARTIFACT_SHA256[OVERVIEW_UI_ASSET]}'\n")
            append("HOME_ZIP_HASH='${EXPECTED_ARTIFACT_SHA256[HOME_UI_ASSET]}'\n")
            append("HOME_TARGET=/system/priv-app/MHSHome/MHSHome.apk\n")
            append("SYSTEMUI_TARGET=/system/system_ext/priv-app/SystemUI/SystemUI.apk\n")
            append("HOME_PATCH_HASH=bb226a5f4b7b2fe86b6f24870798a25c605fa123b4d578a69db51c4d50adf1bd\n")
            append("SYSTEMUI_PATCH_HASH=6814f34f7a382ed197b647294ccd166d78556431ec1cb3d914e2736837c6db14\n")
            append("BRIDGE_HASH=$OVERVIEW_UI_BRIDGE_SHA256\n")
            append("HOME_PATCHER_HASH=$HOME_UI_PATCHER_SHA256\n")
            append("HOME_DELTA_HASH=$HOME_UI_DELTA_SHA256\n")
            append("HOME_BOOT_HASH=$HOME_UI_BOOT_SHA256\n")
            append("HOME_UNINSTALL_HASH=$HOME_UI_UNINSTALL_SHA256\n")
            append("HOME_MODULE_PROP_HASH=$HOME_UI_MODULE_PROP_SHA256\n")
            append("HOME_SKIP_MOUNT_HASH=$HOME_UI_SKIP_MOUNT_SHA256\n")
            append("OVERVIEW_PATCHER_HASH=$OVERVIEW_UI_PATCHER_SHA256\n")
            append("OVERVIEW_DELTA_HASH=$OVERVIEW_UI_DELTA_SHA256\n")
            append("OVERVIEW_BRIDGE_HASH=$OVERVIEW_UI_BRIDGE_SHA256\n")
            append("OVERVIEW_BOOT_HASH=$OVERVIEW_UI_BOOT_SHA256\n")
            append("OVERVIEW_UNINSTALL_HASH=$OVERVIEW_UI_UNINSTALL_SHA256\n")
            append("OVERVIEW_MODULE_PROP_HASH=$OVERVIEW_UI_MODULE_PROP_SHA256\n")
            append("OVERVIEW_SKIP_MOUNT_HASH=$OVERVIEW_UI_SKIP_MOUNT_SHA256\n")

            append("module_dir() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$1\"; [ -d \"\$dir\" ] && [ ! -L \"\$dir\" ] && [ -f \"\$dir/module.prop\" ] && [ ! -L \"\$dir/module.prop\" ] && { echo \"\$dir\"; return 0; }; done; return 1; }\n")
            append("file_hash() { [ -f \"\$1\" ] && [ ! -L \"\$1\" ] && \"\$TIMEOUT\" -k 1s 15s sha256sum \"\$1\" 2>/dev/null | awk '{print \$1}'; }\n")
            append("module_not_blocked() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$1\"; for marker in disable remove; do [ ! -e \"\$dir/\$marker\" ] && [ ! -L \"\$dir/\$marker\" ] || return 1; done; done; return 0; }\n")
            append("module_exact() { module_not_blocked \"\$1\" || return 1; dir=\$(module_dir \"\$1\") || return 1; [ -f \"\$dir/boot-completed.sh\" ] && [ ! -L \"\$dir/boot-completed.sh\" ] && grep -qx \"id=\$1\" \"\$dir/module.prop\" && grep -qx \"version=\$2\" \"\$dir/module.prop\"; }\n")
            append("home_integrity() { dir=\$(module_dir \"\$HOME_ID\") || return 1; [ -x \"\$dir/bin/scbspatch\" ] && [ \"\$(file_hash \"\$dir/bin/scbspatch\")\" = \"\$HOME_PATCHER_HASH\" ] && [ \"\$(file_hash \"\$dir/patch/MHSHome.bsdiff\")\" = \"\$HOME_DELTA_HASH\" ] && [ \"\$(file_hash \"\$dir/boot-completed.sh\")\" = \"\$HOME_BOOT_HASH\" ] && [ \"\$(file_hash \"\$dir/uninstall.sh\")\" = \"\$HOME_UNINSTALL_HASH\" ] && [ \"\$(file_hash \"\$dir/module.prop\")\" = \"\$HOME_MODULE_PROP_HASH\" ] && [ \"\$(file_hash \"\$dir/skip_mount\")\" = \"\$HOME_SKIP_MOUNT_HASH\" ]; }\n")
            append("overview_integrity() { dir=\$(module_dir \"\$OVERVIEW_ID\") || return 1; [ -x \"\$dir/bin/scbspatch\" ] && [ \"\$(file_hash \"\$dir/bin/scbspatch\")\" = \"\$OVERVIEW_PATCHER_HASH\" ] && [ \"\$(file_hash \"\$dir/patch/SystemUI.bsdiff\")\" = \"\$OVERVIEW_DELTA_HASH\" ] && [ \"\$(file_hash \"\$dir/app/SCROverview.apk\")\" = \"\$OVERVIEW_BRIDGE_HASH\" ] && [ \"\$(file_hash \"\$dir/boot-completed.sh\")\" = \"\$OVERVIEW_BOOT_HASH\" ] && [ \"\$(file_hash \"\$dir/uninstall.sh\")\" = \"\$OVERVIEW_UNINSTALL_HASH\" ] && [ \"\$(file_hash \"\$dir/module.prop\")\" = \"\$OVERVIEW_MODULE_PROP_HASH\" ] && [ \"\$(file_hash \"\$dir/skip_mount\")\" = \"\$OVERVIEW_SKIP_MOUNT_HASH\" ]; }\n")
            append("module_integrity() { case \"\$1\" in home) home_integrity ;; overview) overview_integrity ;; *) return 1 ;; esac; }\n")
            append("global_hash() { \"\$NSENTER\" -t 1 -m -- \"\$TIMEOUT\" -k 1s 15s sha256sum \"\$1\" 2>/dev/null | awk '{print \$1}'; }\n")
            append("bridge_hash() { paths=\$(\"\$TIMEOUT\" -k 1s 10s pm path $OVERVIEW_PACKAGE 2>/dev/null) || return 1; count=\$(printf '%s\\n' \"\$paths\" | sed -n 's/^package://p' | wc -l); [ \"\$count\" -eq 1 ] || return 1; path=\$(printf '%s\\n' \"\$paths\" | sed -n 's/^package://p'); [ -n \"\$path\" ] && file_hash \"\$path\"; }\n")
            append("overview_connected() { dir=\$(module_dir \"\$OVERVIEW_ID\") || return 1; state_file=\"\$dir/.scroot-systemui-state.\$\$\"; rm -f \"\$state_file\"; \"\$TIMEOUT\" -k 1s 10s dumpsys activity service com.android.systemui/.SystemUIService > \"\$state_file\" 2>/dev/null || { rm -f \"\$state_file\"; return 1; }; grep -q 'quickStepIntentResolved=true' \"\$state_file\" && grep -q 'isConnected=true' \"\$state_file\" && grep -q 'mCurrentLayout: .*recent' \"\$state_file\"; rc=\$?; rm -f \"\$state_file\"; return \$rc; }\n")
            append("task_broker_ready() { dir=\$(module_dir \"\$OVERVIEW_ID\") || return 1; pid=\$(cat \"\$dir/task-bridge.pid\" 2>/dev/null); case \"\$pid\" in ''|*[!0-9]*) return 1 ;; esac; name=\$({ tr '\\000' '\\n' < \"/proc/\$pid/cmdline\"; } 2>/dev/null | sed -n '1p'); kill -0 \"\$pid\" 2>/dev/null && [ \"\$name\" = 'scr01-task-bridge' ] && grep -q '^protocol=1 uid=0 tasks=[0-9][0-9]*$' \"\$dir/task-bridge.ready\" 2>/dev/null; }\n")
            append("overview_active() { module_exact \"\$OVERVIEW_ID\" \"\$OVERVIEW_VERSION\" && overview_integrity && [ \"\$(global_hash \"\$SYSTEMUI_TARGET\")\" = \"\$SYSTEMUI_PATCH_HASH\" ] && [ \"\$(bridge_hash)\" = \"\$BRIDGE_HASH\" ] && task_broker_ready && pidof com.android.systemui >/dev/null && overview_connected; }\n")
            append("home_active() { module_exact \"\$HOME_ID\" \"\$HOME_VERSION\" && home_integrity && [ \"\$(global_hash \"\$HOME_TARGET\")\" = \"\$HOME_PATCH_HASH\" ] && pidof com.samsung.android.mhshome >/dev/null; }\n")
            append("ui_active() { overview_active && home_active; }\n")
            append("activation_lock_alive() { dir=\$1; lock=\"\$dir/.activation.lock\"; [ -d \"\$lock\" ] || return 1; read -r pid recorded_start < \"\$lock/pid\" 2>/dev/null || return 1; case \"\$pid\" in ''|*[!0-9]*) return 1 ;; esac; current_start=\$(awk '{print \$22}' \"/proc/\$pid/stat\" 2>/dev/null); [ -n \"\$current_start\" ] || return 1; if [ -n \"\$recorded_start\" ]; then [ \"\$current_start\" = \"\$recorded_start\" ]; else cat \"/proc/\$pid/cmdline\" 2>/dev/null | tr '\\000' ' ' | grep -F -q \"\$dir/boot-completed.sh\"; fi; }\n")
            append("activation_flock_held() { lock=\$1; [ -f \"\$lock\" ] && [ ! -L \"\$lock\" ] || return 1; ( exec 0<> \"\$lock\" || exit 0; if \"\$FLOCK\" -n 0; then \"\$FLOCK\" -u 0; exit 1; fi; exit 0 ); }\n")
            append("activation_running() { for lock in \"\$OVERVIEW_LOCK\" \"\$HOME_LOCK\"; do if [ -f \"\$lock\" ] && [ ! -L \"\$lock\" ]; then activation_flock_held \"\$lock\" && return 0; elif [ -e \"\$lock\" ] || [ -L \"\$lock\" ]; then return 0; fi; done; for base in /data/adb/modules_update /data/adb/modules; do for id in \"\$OVERVIEW_ID\" \"\$HOME_ID\"; do dir=\"\$base/\$id\"; lock=\"\$dir/.activation.lock\"; if [ -d \"\$lock\" ]; then activation_lock_alive \"\$dir\" && return 0; elif [ -f \"\$lock\" ] && [ ! -L \"\$lock\" ]; then activation_flock_held \"\$lock\" && return 0; elif [ -e \"\$lock\" ] || [ -L \"\$lock\" ]; then return 0; fi; done; done; return 1; }\n")
            append("wait_for_activation() { count=0; while [ \$count -lt 240 ]; do activation_running || { ui_active && return 0; return 1; }; sleep 1; count=\$((count + 1)); done; return 1; }\n")
            append("install_exact() { id=\$1; version=\$2; zip=\$3; kind=\$4; module_not_blocked \"\$id\" || { echo \"[ui] \$id is disabled or marked for removal\"; return 64; }; if module_exact \"\$id\" \"\$version\" && module_integrity \"\$kind\"; then echo \"[ui] \$id \$version verified\"; return 0; fi; echo \"[ui] repairing \$id \$version from signed-in APK asset\"; \"\$TIMEOUT\" -k 5s 90s \"\$KSUD\" module install \"\$zip\" || return \$?; module_not_blocked \"\$id\" && module_exact \"\$id\" \"\$version\" && module_integrity \"\$kind\"; }\n")
            append("promote_active_uninstall() { id=\$1; expected=\$2; staged=\"/data/adb/modules_update/\$id/uninstall.sh\"; active=\"/data/adb/modules/\$id\"; [ -f \"\$staged\" ] || return 0; [ ! -L \"\$staged\" ] || return 1; [ -d \"\$active\" ] || return 0; [ ! -L \"\$active\" ] || return 1; grep -qx \"id=\$id\" \"\$active/module.prop\" || return 1; [ \"\$(file_hash \"\$staged\")\" = \"\$expected\" ] || return 1; temporary=\"\$active/.scroot-uninstall.\$\$\"; rm -f \"\$temporary\"; if ! cat \"\$staged\" > \"\$temporary\" || ! chmod 0755 \"\$temporary\" || [ \"\$(file_hash \"\$temporary\")\" != \"\$expected\" ] || ! mv -f \"\$temporary\" \"\$active/uninstall.sh\" || [ \"\$(file_hash \"\$active/uninstall.sh\")\" != \"\$expected\" ]; then rm -f \"\$temporary\"; return 1; fi; }\n")
            append("promote_verified_active_uninstalls() { promoted=0; if [ -f \"/data/adb/modules_update/\$OVERVIEW_ID/module.prop\" ] && module_exact \"\$OVERVIEW_ID\" \"\$OVERVIEW_VERSION\" && overview_integrity; then promote_active_uninstall \"\$OVERVIEW_ID\" \"\$OVERVIEW_UNINSTALL_HASH\" || return 1; promoted=1; fi; if [ -f \"/data/adb/modules_update/\$HOME_ID/module.prop\" ] && module_exact \"\$HOME_ID\" \"\$HOME_VERSION\" && home_integrity; then promote_active_uninstall \"\$HOME_ID\" \"\$HOME_UNINSTALL_HASH\" || return 1; promoted=1; fi; [ \"\$promoted\" = 0 ] || \"\$TIMEOUT\" -k 2s 20s \"\$SYNC\"; }\n")
            append("[ -x \"\$TIMEOUT\" ] && [ -x \"\$NSENTER\" ] && [ -x \"\$KSUD\" ] && [ -x \"\$SYNC\" ] && [ -x \"\$FLOCK\" ] || exit 50\n")
            append("[ \"\$(file_hash \"\$OVERVIEW_ZIP\")\" = \"\$OVERVIEW_ZIP_HASH\" ] || exit 51\n")
            append("[ \"\$(file_hash \"\$HOME_ZIP\")\" = \"\$HOME_ZIP_HASH\" ] || exit 52\n")
            append("promote_verified_active_uninstalls || exit 61\n")
            append("if ui_active; then echo 'UI_READY already-active'; exit 0; fi\n")
            append("if activation_running; then echo '[ui] waiting for KernelSU module activation'; wait_for_activation && { echo 'UI_READY late-load-active'; exit 0; }; activation_running && exit 60; fi\n")
            append("install_exact \"\$OVERVIEW_ID\" \"\$OVERVIEW_VERSION\" \"\$OVERVIEW_ZIP\" overview || exit 53\n")
            append("install_exact \"\$HOME_ID\" \"\$HOME_VERSION\" \"\$HOME_ZIP\" home || exit 54\n")
            append("promote_verified_active_uninstalls || exit 62\n")
            append("OVERVIEW_DIR=\$(module_dir \"\$OVERVIEW_ID\") || exit 55\n")
            append("HOME_DIR=\$(module_dir \"\$HOME_ID\") || exit 56\n")
            append("module_exact \"\$OVERVIEW_ID\" \"\$OVERVIEW_VERSION\" && overview_integrity || exit 55\n")
            append("module_exact \"\$HOME_ID\" \"\$HOME_VERSION\" && home_integrity || exit 56\n")
            append("if overview_active; then echo '[ui] native Recents and Apps bridge already active'; else echo '[ui] activating native Recents and Apps bridge'; \"\$TIMEOUT\" -k 30s 110s /system/bin/sh \"\$OVERVIEW_DIR/boot-completed.sh\"; OVERVIEW_RC=\$?; tail -n 12 \"\$OVERVIEW_DIR/service.log\" 2>/dev/null; [ \$OVERVIEW_RC -eq 0 ] || exit 57; fi\n")
            append("if home_active; then echo '[ui] stock Home menu and swipe gesture already active'; else echo '[ui] activating stock Home menu and swipe gesture'; \"\$TIMEOUT\" -k 30s 110s /system/bin/sh \"\$HOME_DIR/boot-completed.sh\"; HOME_RC=\$?; tail -n 12 \"\$HOME_DIR/service.log\" 2>/dev/null; [ \$HOME_RC -eq 0 ] || exit 58; fi\n")
            append("ui_active || { activation_running && wait_for_activation; } || exit 59\n")
            append("echo 'UI_READY installed-and-active'\n")
        }
        val result = captureRootScript(
            script,
            runTimeoutMs = 420_000L,
            killGraceMs = 45_000L
        )
        result.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
        var ready = !result.timedOut && result.rc == 0 &&
            result.lines.any { it.startsWith("UI_READY ") }
        if (!ready && provisionFailureMayUseLiveRecovery(result.rc, result.timedOut)) {
            val health = probeSystemUiLiveHealth(ctx)
            log("  [recovery] retrying the safely rolled-back UI component")
            ready = recoverProvisionedSystemUiLive(health, log) &&
                verifyProvisionedSystemUiLive(ctx)
        }
        if (!ready) {
            log(
                "[ERROR] Launcher/SystemUI integration failed " +
                    "(exit=${result.rc}, timeout=${result.timedOut})."
            )
            return false
        }
        if (!verifyProvisionedSystemUiLive(ctx)) {
            val health = probeSystemUiLiveHealth(ctx)
            if (!recoverProvisionedSystemUiLive(health, log) ||
                !verifyProvisionedSystemUiLive(ctx)
            ) {
                val cleared = clearSystemUiReceipt(ctx)
                log(
                    if (cleared) {
                        "[ERROR] Launcher integration did not pass the live build health check."
                    } else {
                        "[ERROR] Launcher live health failed and its receipt could not be cleared."
                    }
                )
                return false
            }
        }
        if (!writeSystemUiReceipt(ctx)) {
            log("[ERROR] Launcher integration succeeded, but its boot receipt was not saved.")
            return false
        }
        if (!isSystemUiIntegratedForCurrentBoot(ctx)) {
            val cleared = clearSystemUiReceipt(ctx)
            log(
                if (cleared) {
                    "[ERROR] Launcher integration changed before its receipt was committed."
                } else {
                    "[ERROR] Launcher commit verification failed and its receipt could not be cleared."
                }
            )
            return false
        }
        log("  [OK] Apps screen, Root menu and native Recents are active")
        return true
    }

    private fun moduleManagerAppId(): Int? =
        try {
            File("/sys/module/ksu_glue/parameters/manager_appid")
                .readText().trim().toLongOrNull()
                ?.takeIf { it in 0..99_999 }?.toInt()
        } catch (_: Exception) { null }

    data class MemoryState(
        val freeKb: Long,
        val availableKb: Long,
        val cachedKb: Long
    )

    private data class PsiSnapshot(
        val someAvg10: Double,
        val fullAvg10: Double,
        val someTotalUs: Long,
        val fullTotalUs: Long
    )

    private data class VmState(
        val workingsetRefault: Long,
        val pgscanDirect: Long,
        val pgstealDirect: Long,
        val allocstall: Long
    )

    private data class ProcRead(val text: String?, val detail: String)
    private var lastPsiReadDetail = "not sampled"
    private var lastVmReadDetail = "not sampled"
    private val restrictedProcPaths = ConcurrentHashMap.newKeySet<String>()

    private fun memoryState(): MemoryState {
        var free = 0L
        var available = 0L
        var cached = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 2) return@forEachLine
                val value = fields[1].toLongOrNull() ?: return@forEachLine
                when (fields[0]) {
                    "MemFree:" -> free = value
                    "MemAvailable:" -> available = value
                    "Cached:" -> cached = value
                }
            }
        } catch (_: Exception) {}
        return MemoryState(free, available, cached)
    }

    private fun readProcText(path: String, maxChars: Int = 128 * 1024): ProcRead {
        if (restrictedProcPaths.contains(path)) {
            return ProcRead(null, "SELinux-restricted in the app domain")
        }
        var fd: java.io.FileDescriptor? = null
        val directError: String
        try {
            fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            val buffer = ByteArray(8 * 1024)
            val text = StringBuilder()
            while (text.length < maxChars) {
                val remaining = maxChars - text.length
                val count = Os.read(fd, buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                text.append(String(buffer, 0, count, Charsets.US_ASCII))
            }
            return ProcRead(text.toString(), "direct")
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) {

                restrictedProcPaths.add(path)
                return ProcRead(null, "SELinux-restricted in the app domain")
            }
            directError = "${e.javaClass.simpleName}:${e.message ?: "no detail"}"
        } catch (e: Exception) {
            directError = "${e.javaClass.simpleName}:${e.message ?: "no detail"}"
        } finally {
            fd?.let {
                try { Os.close(it) } catch (_: Exception) {}
            }
        }

        val fallback = execBuffered(
            "/system/bin/cat",
            listOf(path),
            timeoutMs = 2_000
        )
        if (!fallback.timedOut && fallback.rc == 0 && fallback.lines.isNotEmpty()) {
            return ProcRead(
                fallback.lines.joinToString(separator = "\n", postfix = "\n"),
                "cat fallback ($directError)"
            )
        }
        return ProcRead(
            null,
            "direct=$directError cat_rc=${fallback.rc} cat_timeout=${fallback.timedOut} " +
                "cat=${fallback.lines.lastOrNull() ?: "no output"}"
        )
    }

    private fun psiSnapshot(): PsiSnapshot? {
        var someAvg10: Double? = null
        var fullAvg10: Double? = null
        var someTotalUs: Long? = null
        var fullTotalUs: Long? = null
        val read = readProcText("/proc/pressure/memory")
        if (read.text == null) {
            lastPsiReadDetail = read.detail
            return null
        }
        return try {
            read.text.lineSequence().forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.isEmpty()) return@forEach
                val values = fields.drop(1).mapNotNull { field ->
                    val split = field.split("=", limit = 2)
                    if (split.size == 2) split[0] to split[1] else null
                }.toMap()
                when (fields[0]) {
                    "some" -> {
                        someAvg10 = values["avg10"]?.toDoubleOrNull()
                        someTotalUs = values["total"]?.toLongOrNull()
                    }
                    "full" -> {
                        fullAvg10 = values["avg10"]?.toDoubleOrNull()
                        fullTotalUs = values["total"]?.toLongOrNull()
                    }
                }
            }
            val parsedSomeAvg10 = someAvg10
            val parsedFullAvg10 = fullAvg10
            val parsedSomeTotalUs = someTotalUs
            val parsedFullTotalUs = fullTotalUs
            if (parsedSomeAvg10 != null && parsedFullAvg10 != null &&
                parsedSomeTotalUs != null && parsedFullTotalUs != null) {
                lastPsiReadDetail = read.detail
                PsiSnapshot(
                    parsedSomeAvg10,
                    parsedFullAvg10,
                    parsedSomeTotalUs,
                    parsedFullTotalUs
                )
            } else {
                lastPsiReadDetail = "${read.detail}; parse incomplete"
                null
            }
        } catch (e: Exception) {
            lastPsiReadDetail = "${read.detail}; parse=${e.javaClass.simpleName}:${e.message}"
            null
        }
    }

    private fun vmState(): VmState? {
        var refault = 0L
        var scanDirect = 0L
        var scanDirectZones = 0L
        var stealDirect = 0L
        var stealDirectZones = 0L
        var allocstall = 0L
        val read = readProcText("/proc/vmstat")
        if (read.text == null) {
            lastVmReadDetail = read.detail
            return null
        }
        return try {
            read.text.lineSequence().forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size != 2) return@forEach
                val value = fields[1].toLongOrNull() ?: return@forEach
                when {
                    fields[0] == "workingset_refault" -> refault = value
                    fields[0] == "pgscan_direct" -> scanDirect = value
                    fields[0].startsWith("pgscan_direct_") -> scanDirectZones += value
                    fields[0] == "pgsteal_direct" -> stealDirect = value
                    fields[0].startsWith("pgsteal_direct_") -> stealDirectZones += value
                    fields[0] == "allocstall" || fields[0].startsWith("allocstall_") ->
                        allocstall += value
                }
            }
            lastVmReadDetail = read.detail
            VmState(
                refault,
                scanDirect.takeIf { it > 0L } ?: scanDirectZones,
                stealDirect.takeIf { it > 0L } ?: stealDirectZones,
                allocstall
            )
        } catch (e: Exception) {
            lastVmReadDetail = "${read.detail}; parse=${e.javaClass.simpleName}:${e.message}"
            null
        }
    }

    private fun vmDelta(before: VmState?, after: VmState?): String {
        if (before == null || after == null) return "unavailable"
        fun delta(a: Long, b: Long) = (b - a).coerceAtLeast(0L)
        return "refault=${delta(before.workingsetRefault, after.workingsetRefault)} " +
            "scan_direct=${delta(before.pgscanDirect, after.pgscanDirect)} " +
            "steal_direct=${delta(before.pgstealDirect, after.pgstealDirect)} " +
            "allocstall=${delta(before.allocstall, after.allocstall)}"
    }

    internal fun fallbackMemorySampleIsQuiet(
        availableKb: Long,
        previousAvailableKb: Long?,
        lowMemory: Boolean,
        thresholdKb: Long
    ): Boolean {
        val enoughHeadroom = availableKb >= MIN_AVAILABLE_KB &&
            availableKb >= thresholdKb + FALLBACK_AVAILABLE_HEADROOM_KB
        val stable = previousAvailableKb == null ||
            availableKb >= previousAvailableKb - FALLBACK_MAX_DROP_KB
        return enoughHeadroom && stable && !lowMemory
    }

    internal fun nativeEntryMemoryIsSafe(memory: MemoryState): Boolean =
        memory.freeKb >= HARD_FREE_FLOOR_KB &&
            memory.availableKb >= MIN_AVAILABLE_KB

    private fun waitForMemoryFallback(
        ctx: Context,
        log: (String) -> Unit,
        deadlineUptimeS: Double,
        label: String,
        minimumFreeKb: Long
    ): Boolean {
        val manager = ctx.getSystemService(ActivityManager::class.java)
        var previousAvailableKb: Long? = null
        var consecutiveQuiet = 0
        var window = 0
        while (uptimeSeconds() < deadlineUptimeS) {
            val remainingMs = ((deadlineUptimeS - uptimeSeconds()) * 1000.0)
                .toLong().coerceAtLeast(1L)
            Thread.sleep(PSI_SAMPLE_MS.coerceAtMost(remainingMs))

            val info = ActivityManager.MemoryInfo()
            try {
                manager.getMemoryInfo(info)
            } catch (e: Exception) {
                log("  [ERROR] $label: ActivityManager memory sample failed: ${e.message}")
                return false
            }
            val procMemory = memoryState()
            val amAvailableKb = info.availMem / 1024
            val availableKb = when {
                procMemory.availableKb <= 0 -> amAvailableKb
                amAvailableKb <= 0 -> procMemory.availableKb
                else -> minOf(procMemory.availableKb, amAvailableKb)
            }
            val thresholdKb = info.threshold / 1024
            val quiet = fallbackMemorySampleIsQuiet(
                availableKb,
                previousAvailableKb,
                info.lowMemory,
                thresholdKb
            ) && (minimumFreeKb <= 0 || procMemory.freeKb >= minimumFreeKb)
            consecutiveQuiet = if (quiet) consecutiveQuiet + 1 else 0
            window++
            log(
                "  [memory] $label#$window available=${availableKb}KiB " +
                    "free=${procMemory.freeKb}KiB " +
                    "threshold=${thresholdKb}KiB low=${info.lowMemory} " +
                    "stable=$consecutiveQuiet/$PSI_QUIET_WINDOWS"
            )
            if (availableKb < MIN_AVAILABLE_KB ||
                availableKb < thresholdKb + FALLBACK_AVAILABLE_HEADROOM_KB ||
                info.lowMemory ||
                (minimumFreeKb > 0 && procMemory.freeKb < minimumFreeKb)
            ) {
                log("  [ERROR] $label: memory headroom is below the safety gate.")
                return false
            }
            if (consecutiveQuiet >= PSI_QUIET_WINDOWS) return true
            previousAvailableKb = availableKb
        }
        log("  [ERROR] $label: stable memory window was not reached before the deadline.")
        return false
    }

    private fun waitForQuietWindow(
        ctx: Context,
        log: (String) -> Unit,
        notBeforeUptimeS: Double,
        deadlineUptimeS: Double,
        label: String,
        minimumFreeKb: Long = 0,
        warnAgainstInteraction: Boolean = false
    ): Boolean {
        val uptime = uptimeSeconds()
        val waitMs = ((notBeforeUptimeS - uptime) * 1000.0)
            .toLong().coerceAtLeast(0L)
        if (waitMs > 0L) {
            log("  $label: waiting ${"%.1f".format(waitMs / 1000.0)}s for minimum uptime")
        }
        if (warnAgainstInteraction && waitMs == 0L) {

            log("  [CAUTION] allocator stability gate active")
        }
        if (waitMs > 0L) Thread.sleep(waitMs)

        var previous: PsiSnapshot = psiSnapshot() ?: run {
            log("  $label: PSI unavailable — using ActivityManager stability gates")
            log("  [psi] reader=$lastPsiReadDetail")
            return waitForMemoryFallback(
                ctx,
                log,
                deadlineUptimeS,
                label,
                minimumFreeKb
            )
        }
        log("  [psi] reader=$lastPsiReadDetail")

        var previousAtMs = SystemClock.elapsedRealtime()
        var consecutiveQuiet = 0
        var window = 0
        while (uptimeSeconds() < deadlineUptimeS) {
            val remainingMs = ((deadlineUptimeS - uptimeSeconds()) * 1000.0)
                .toLong().coerceAtLeast(1L)
            Thread.sleep(PSI_SAMPLE_MS.coerceAtMost(remainingMs))

            val currentAtMs = SystemClock.elapsedRealtime()
            val current = psiSnapshot()
            if (current == null) {
                log("  $label: PSI became unavailable — switching to ActivityManager gates")
                log("  [psi] reader=$lastPsiReadDetail")
                return waitForMemoryFallback(
                    ctx,
                    log,
                    deadlineUptimeS,
                    label,
                    minimumFreeKb
                )
            }
            val elapsedUs = ((currentAtMs - previousAtMs).coerceAtLeast(1L)) * 1000.0
            val someDeltaUs = (current.someTotalUs - previous.someTotalUs).coerceAtLeast(0L)
            val fullDeltaUs = (current.fullTotalUs - previous.fullTotalUs).coerceAtLeast(0L)
            val somePercent = someDeltaUs * 100.0 / elapsedUs
            val fullPercent = fullDeltaUs * 100.0 / elapsedUs
            val memory = memoryState()
            val quiet = somePercent <= MAX_PSI_SOME_STALL_PERCENT &&
                fullPercent <= MAX_PSI_FULL_STALL_PERCENT &&
                memory.availableKb >= MIN_AVAILABLE_KB &&
                (minimumFreeKb <= 0 || memory.freeKb >= minimumFreeKb)

            window++
            consecutiveQuiet = if (quiet) consecutiveQuiet + 1 else 0
            log(
                "  [psi] $label#$window some=${"%.2f".format(somePercent)}% " +
                    "full=${"%.2f".format(fullPercent)}% " +
                    "avg10=${"%.2f".format(current.someAvg10)}/" +
                    "${"%.2f".format(current.fullAvg10)} " +
                    "free=${memory.freeKb}KiB " +
                    "available=${memory.availableKb}KiB " +
                    "quiet=$consecutiveQuiet/$PSI_QUIET_WINDOWS"
            )
            if (memory.availableKb < MIN_AVAILABLE_KB ||
                (minimumFreeKb > 0 && memory.freeKb < minimumFreeKb)
            ) {
                log("  [ERROR] Memory fell below the safety floor during $label.")
                return false
            }
            if (consecutiveQuiet >= PSI_QUIET_WINDOWS) return true

            previous = current
            previousAtMs = currentAtMs
        }
        log("  [ERROR] $label: PSI quiet window was not reached before the deadline.")
        return false
    }

    private fun executionClass(): Pair<String, String> {
        val raw = try { File("/proc/self/cgroup").readText().trim() }
                  catch (_: Exception) { "" }
        val kind = when {
            raw.contains("cpuset:/top-app") -> "top-app"
            raw.contains("cpuset:/foreground") -> "foreground"
            raw.contains("cpuset:/background") -> "background"
            else -> "unknown"
        }
        return Pair(kind, raw.replace("\n", " | "))
    }

    internal fun executionClassAllowed(mode: ExecutionMode, executionClass: String): Boolean =
        when (mode) {
            ExecutionMode.MANUAL -> executionClass == "top-app"
            ExecutionMode.AUTO ->
                executionClass == "top-app" || executionClass == "foreground"
        }

    private fun uptimeSeconds(): Double = SystemClock.elapsedRealtime() / 1000.0

    internal fun exploitWindowExpired(uptimeSeconds: Double): Boolean =
        !uptimeSeconds.isFinite() ||
            uptimeSeconds < 0.0 ||
            uptimeSeconds > MAX_EXPLOIT_UPTIME_S

    internal fun currentExploitWindowExpired(): Boolean =
        exploitWindowExpired(uptimeSeconds())

    data class Result(
        val rooted: Boolean,
        val moduleLoaded: Boolean,
        val managerCrowned: Boolean,
        val userspaceReady: Boolean,
        val exploitAttempted: Boolean,
        val systemUiIntegrated: Boolean = false
    )

    fun isSystemUiIntegratedForCurrentBoot(ctx: Context): Boolean {
        val appContext = ctx.applicationContext
        val bootId = AutoRootPreferences.currentBootId() ?: return false
        val expected = expectedSystemUiReceipt(bootId)
        val receiptFile = File(appContext.filesDir, UI_RECEIPT_FILE)
        if (!isRegularFileNoFollow(receiptFile) || receiptFile.length() !in 1L..512L) {
            return false
        }
        val receipt = try {
            receiptFile.readLines()
        } catch (_: Exception) {
            return false
        }
        if (receipt != expected) return false
        return probeSystemUiLiveHealth(appContext).ready
    }

    private fun probeSystemUiLiveHealth(ctx: Context): SystemUiLiveHealth {
        val appContext = ctx.applicationContext
        val packageReady = installedPackageHashMatches(
            appContext,
            OVERVIEW_PACKAGE,
            OVERVIEW_UI_BRIDGE_SHA256
        )
        val health = try {
            if (packageReady) {
                appContext.contentResolver.call(
                    Uri.parse(OVERVIEW_HEALTH_URI),
                    OVERVIEW_HEALTH_METHOD,
                    null,
                    null
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val overviewReady = health != null && overviewHealthSignalsReady(
            protocol = health.getInt("protocol", -1),
            buildId = health.getString(OVERVIEW_HEALTH_BUILD_KEY),
            quickstepBound = health.getBoolean("quickstep_bound", false),
            brokerReady = health.getBoolean("broker_ready", false)
        )
        return SystemUiLiveHealth(
            overviewReady = overviewReady,
            homeReady = isHomeUiLive(appContext)
        )
    }

    internal fun overviewHealthSignalsReady(
        protocol: Int,
        buildId: String?,
        quickstepBound: Boolean,
        brokerReady: Boolean
    ): Boolean = protocol == OVERVIEW_HEALTH_PROTOCOL &&
        buildId == OVERVIEW_HEALTH_BUILD_ID &&
        quickstepBound && brokerReady

    internal fun homeHealthSignalsReady(
        resultCode: Int,
        protocol: Int,
        buildId: String?,
        expectedNonce: String,
        returnedNonce: String?,
        homeReady: Boolean
    ): Boolean = resultCode == HOME_HEALTH_RESULT_CODE &&
        protocol == HOME_HEALTH_PROTOCOL &&
        buildId == HOME_HEALTH_BUILD_ID &&
        expectedNonce.isNotEmpty() &&
        returnedNonce == expectedNonce &&
        homeReady

    private fun isHomeUiLive(ctx: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val nonce = UUID.randomUUID().toString()
        val completed = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = getResultExtras(false)
                ready.set(
                    homeHealthSignalsReady(
                        resultCode = resultCode,
                        protocol = extras?.getInt("protocol", -1) ?: -1,
                        buildId = extras?.getString(HOME_HEALTH_BUILD_KEY),
                        expectedNonce = nonce,
                        returnedNonce = extras?.getString(HOME_HEALTH_NONCE_KEY),
                        homeReady = extras?.getBoolean("home_ready", false) == true
                    )
                )
                completed.countDown()
            }
        }
        return try {
            ctx.sendOrderedBroadcast(
                Intent(HOME_HEALTH_ACTION)
                    .setPackage(HOME_PACKAGE)
                    .putExtra(HOME_HEALTH_NONCE_KEY, nonce),
                null,
                resultReceiver,
                Handler(Looper.getMainLooper()),
                0,
                null,
                null
            )
            completed.await(HOME_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ready.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun expectedSystemUiReceipt(bootId: String): List<String> = listOf(
        "format=2",
        bootId,
        "$HOME_UI_MODULE_ID=$HOME_UI_VERSION",
        "$OVERVIEW_UI_MODULE_ID=$OVERVIEW_UI_VERSION",
        "home_archive=${EXPECTED_ARTIFACT_SHA256.getValue(HOME_UI_ASSET)}",
        "overview_archive=${EXPECTED_ARTIFACT_SHA256.getValue(OVERVIEW_UI_ASSET)}"
    )

    internal fun metaModuleVerificationShell(): String = buildString {
        append("META_ID='$EXPECTED_META_ID'\n")
        append("META_VERSION='$EXPECTED_META_VERSION'\n")
        append("META_MODULE_PROP_HASH='$META_MODULE_PROP_SHA256'\n")
        append("META_BINARY_HASH='$META_BINARY_SHA256'\n")
        append("META_INSTALL_HASH='$META_INSTALL_SHA256'\n")
        append("META_MOUNT_HASH='$META_MOUNT_SHA256'\n")
        append("META_POST_MOUNT_HASH='$META_POST_MOUNT_SHA256'\n")
        append("META_UNINSTALL_HASH='$META_UNINSTALL_SHA256'\n")
        append("META_MODULE_UNINSTALL_HASH='$META_MODULE_UNINSTALL_SHA256'\n")
        append("meta_file_hash() { [ -f \"\$1\" ] && [ ! -L \"\$1\" ] && /system/bin/timeout -k 1s 15s sha256sum \"\$1\" 2>/dev/null | awk '{print \$1}'; }\n")
        append("meta_dir() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$META_ID\"; [ -d \"\$dir\" ] && [ ! -L \"\$dir\" ] && [ -f \"\$dir/module.prop\" ] && [ ! -L \"\$dir/module.prop\" ] && { printf '%s\\n' \"\$dir\"; return 0; }; done; return 1; }\n")
        append("meta_not_blocked() { for base in /data/adb/modules_update /data/adb/modules; do dir=\"\$base/\$META_ID\"; for marker in disable remove; do [ ! -e \"\$dir/\$marker\" ] && [ ! -L \"\$dir/\$marker\" ] || return 1; done; done; return 0; }\n")
        append("meta_exact() { meta_not_blocked || return 1; dir=\$(meta_dir) || return 1; grep -qx \"id=\$META_ID\" \"\$dir/module.prop\" && grep -qx \"version=\$META_VERSION\" \"\$dir/module.prop\" && grep -qx 'metamodule=1' \"\$dir/module.prop\" && [ \"\$(meta_file_hash \"\$dir/module.prop\")\" = \"\$META_MODULE_PROP_HASH\" ] && [ -x \"\$dir/meta-overlayfs\" ] && [ \"\$(meta_file_hash \"\$dir/meta-overlayfs\")\" = \"\$META_BINARY_HASH\" ] && [ \"\$(meta_file_hash \"\$dir/metainstall.sh\")\" = \"\$META_INSTALL_HASH\" ] && [ \"\$(meta_file_hash \"\$dir/metamount.sh\")\" = \"\$META_MOUNT_HASH\" ] && [ \"\$(meta_file_hash \"\$dir/post-mount.sh\")\" = \"\$META_POST_MOUNT_HASH\" ] && [ \"\$(meta_file_hash \"\$dir/metauninstall.sh\")\" = \"\$META_UNINSTALL_HASH\" ] && [ \"\$(meta_file_hash \"\$dir/uninstall.sh\")\" = \"\$META_MODULE_UNINSTALL_HASH\" ]; }\n")
    }

    fun run(
        ctx: Context,
        maxExploitTries: Int,
        launchManager: Boolean = true,
        executionMode: ExecutionMode,
        ui: (String) -> Unit
    ): Result {
        if (!flowRunning.compareAndSet(false, true)) {
            val message = "[BLOCKED] Another SCRoot pipeline is already running."
            ui(message)
            bootLog(ctx, message)
            return Result(false, isModuleLoaded(), false, false, false)
        }
        return try {
            runLocked(ctx, maxExploitTries, launchManager, executionMode, ui)
        } finally {
            flowRunning.set(false)
        }
    }

    private fun runLocked(
        ctx: Context,
        maxExploitTries: Int,
        launchManager: Boolean,
        executionMode: ExecutionMode,
        ui: (String) -> Unit
    ): Result {
        val log: (String) -> Unit = { line -> ui(line); bootLog(ctx, line) }
        val nl = ctx.applicationInfo.nativeLibraryDir
        val exploit = "$nl/libexploit.so"
        val rootsh = "$nl/librootsh.so"
        val bootstrap = "$nl/libbootstrap.so"
        val memoryPrepare = "$nl/libmemprep.so"
        val ksud = "$nl/libksud.so"
        val ksuCheck = "$nl/libksucheck.so"
        val adbRoot = "$nl/libadbroot.so"

        log("[target] ${Build.FINGERPRINT}")
        log(
            "[target] manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "device=${Build.DEVICE} product=${Build.PRODUCT} id=${Build.ID} " +
                "incremental=${Build.VERSION.INCREMENTAL} sdk=${Build.VERSION.SDK_INT} " +
                "kernel=${System.getProperty("os.version")} " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull()}"
        )
        val targetMismatch = targetMismatch()
        if (targetMismatch != null) {
            log("[ERROR] Unsupported exact firmware profile ($targetMismatch)")
            log("  Physical kernel patching is blocked fail-closed.")
            log("  expected: $EXPECTED_FINGERPRINT / $EXPECTED_KERNEL_RELEASE")
            return Result(false, false, false, false, false)
        }
        if (!File(exploit).canExecute() || !File(rootsh).canExecute() ||
            !File(bootstrap).canExecute() || !File(memoryPrepare).canExecute() ||
            !File(ksud).canExecute() || !File(ksuCheck).canExecute()) {
            log("[ERROR] The bundled native payload is not executable.")
            return Result(false, false, false, false, false)
        }
        val nativeArtifacts = listOf(
            "libexploit.so" to File(exploit),
            "librootsh.so" to File(rootsh),
            "libbootstrap.so" to File(bootstrap),
            "libmemprep.so" to File(memoryPrepare),
            "libksud.so" to File(ksud),
            "libksuglue.so" to File(nl, "libksuglue.so"),
            "libksucheck.so" to File(ksuCheck),
            "libadbroot.so" to File(adbRoot)
        )
        val invalidNative = nativeArtifacts.filterNot { (name, file) ->
            verifyArtifact(file, name)
        }.map { it.first }
        if (invalidNative.isNotEmpty()) {
            log("[ERROR] APK native payload SHA-256 mismatch: ${invalidNative.joinToString()}")
            return Result(false, false, false, false, false)
        }
        log("  payload integrity: native ${nativeArtifacts.size}/${nativeArtifacts.size}")

        val apk = stageAsset(ctx, "manager.apk")
        if (apk == null || !verifyArtifact(apk, "manager.apk")) {
            log("[ERROR] Failed to stage the bundled manager.apk.")
            return Result(false, false, false, false, false)
        }
        val meta = stageAsset(ctx, "meta-overlayfs-scr01.zip")
        if (meta == null || !verifyArtifact(meta, "meta-overlayfs-scr01.zip")) {
            log("[ERROR] Failed to stage the bundled SCR-01 metamodule.")
            return Result(false, false, false, false, false)
        }
        log("  payload integrity: manager + metamodule 2/2")
        val preManager = verifyManager(ctx, apk, requireInstalled = false)
        log("  manager preflight: ${preManager.detail}")
        if (!preManager.ok) {
            log("[ERROR] Package impersonation or bundle damage detected — exploit blocked.")
            return Result(false, false, false, false, false)
        }

        val initialModuleState = probeModuleState()
        if (initialModuleState == ModuleState.UNKNOWN) {
            val recordedAttempt = AutoRootPreferences.currentExploitAttempt(ctx)
            if (!unknownModuleStateMayStartFresh(
                    initialModuleState,
                    exploitRecorded = recordedAttempt != null
                )) {
                log("[ERROR] Kernel module state is unreadable after a recorded attempt.")
                log("  Reboot before another exploit attempt.")
                return Result(false, false, false, false, false)
            }

            log("  module preflight: clean boot (app procfs restricted, no attempt recorded)")
        }
        if (initialModuleState == ModuleState.PRESENT) {
            var installed = verifyManager(ctx, apk, requireInstalled = true)
            var expectedAppId = if (installed.uid >= 0) installed.uid % 100_000 else -1
            var moduleAppId = moduleManagerAppId()
            log("[steady] ksu_glue already live — exploit rerun blocked")
            log("  manager=${installed.detail} module_appid=${moduleAppId ?: "legacy"}")
            val managerRefresh = if (installed.ok) {
                ""
            } else {
                "pm install -r -g '${apk.absolutePath}' || exit 37; "
            }
            val steadyScript =
                managerRefresh +
                    metaModuleVerificationShell() +
                    "pm enable $MANAGER_PKG >/dev/null || exit 37; " +
                    "pm path $MANAGER_PKG >/dev/null 2>&1 || exit 37; " +
                    "APP_UID=\$(pm list packages -U $MANAGER_PKG | " +
                    "awk -v target='package:$MANAGER_PKG' " +
                    "'\$1 == target { sub(/^uid:/, \"\", \$2); print \$2; exit }'); " +
                    "case \"\$APP_UID\" in ''|*[!0-9]*) exit 38 ;; esac; " +
                    "APP_ID=\$((APP_UID % 100000)); " +
                    "echo \$APP_ID > /sys/module/ksu_glue/parameters/manager_appid || exit 38; " +
                    "echo 1 > /sys/module/ksu_glue/parameters/restore_sel_read_enforce || exit 41; " +
                    "am force-stop $MANAGER_PKG; " +
                    "/data/adb/ksud -V >/dev/null || exit 31; " +
                    "META_CHANGED=0; " +
                    "if ! meta_exact; then meta_not_blocked || exit 32; " +
                    "/data/adb/ksud module install '${meta.absolutePath}' || exit 32; " +
                    "META_CHANGED=1; meta_exact || exit 32; fi; " +
                    "READY=\$(cat /sys/module/ksu_glue/parameters/userspace_ready 2>/dev/null); " +
                    "if [ \"\$META_CHANGED\" = 1 ] || [ \"\$READY\" != Y ]; then " +

                    "'$ksud' late-load --allow-shell --package-name $MANAGER_PKG || exit 33; " +
                    "sleep 2; fi; " +
                    "'$ksud' install --libadbroot '$adbRoot' || exit 40; " +
                    "/data/adb/ksud module metamodule >/dev/null || exit 34; " +
                    "/data/adb/ksud module list >/dev/null || exit 35; " +
                    "'$ksuCheck' umount verify-global || exit 42; " +
                    "'$ksuCheck' check ${ctx.applicationInfo.uid} || exit 39; " +
                    "echo Y > /sys/module/ksu_glue/parameters/userspace_ready || exit 36; " +
                    if (launchManager) {
                        "true"
                    } else {
                        "am force-stop $MANAGER_PKG >/dev/null 2>&1 || true"
                    }
            var ksudInstall = captureRootScript(steadyScript, runTimeoutMs = 45_000L)
            if (ksudInstall.rc != 0 || ksudInstall.timedOut) {
                log("  clean-state ksud seed recovery — temporary root hook only (no exploit rerun)")
                val seed = capture(
                    bootstrap,
                    "$nl/libksuglue.so",
                    "enable_sucompat=1 enable_manager_fd=1 enable_setcon=1 " +
                        "bootstrap_appid=${ctx.applicationInfo.uid % 100_000}",
                    ksud,
                    timeoutMs = 30_000
                )
                seed.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
                log("  clean-state seed exit=${seed.rc} timeout=${seed.timedOut}")
                if (!seed.timedOut && seed.rc == 0) {
                    ksudInstall = captureRootScript(steadyScript, runTimeoutMs = 45_000L)
                }
            }
            val ksudReady = !ksudInstall.timedOut && ksudInstall.rc == 0
            ksudInstall.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
            log(
                "  ksud=" + if (ksudReady) {
                    "official v3.3.0 ready"
                } else {
                    "install/verification failed"
                }
            )
            if (ksudReady) {
                installed = verifyManager(ctx, apk, requireInstalled = true)
                expectedAppId = if (installed.uid >= 0) installed.uid % 100_000 else -1
                log("  manager post-seed: ${installed.detail}")
            }
            if (installed.ok && ksudReady && moduleAppId != expectedAppId) {
                log("  repairing manager crown mismatch")
                val recoverScript =
                    "echo $expectedAppId > /sys/module/ksu_glue/parameters/manager_appid && " +
                        "echo 1 > /sys/module/ksu_glue/parameters/restore_sel_read_enforce && " +
                        "am force-stop $MANAGER_PKG" +
                        if (launchManager) {
                            " && am start -n $MANAGER_PKG/.ui.MainActivity"
                        } else {
                            ""
                        }
                val recover = captureRootScript(recoverScript, runTimeoutMs = 20_000L)
                recover.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
                moduleAppId = moduleManagerAppId()
            } else if (installed.ok && launchManager) {
                ctx.packageManager.getLaunchIntentForPackage(MANAGER_PKG)?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            }
            val crowned = installed.ok && moduleAppId == expectedAppId
            val systemUiIntegrated = if (crowned && ksudReady) {
                provisionSystemUi(ctx, log)
            } else {
                log("[WARNING] Launcher integration deferred until KernelSU setup is healthy.")
                false
            }
            return Result(
                rooted = true,
                moduleLoaded = true,
                managerCrowned = crowned,
                userspaceReady = ksudReady,
                exploitAttempted = false,
                systemUiIntegrated = systemUiIntegrated
            )
        }

        log("[1/3] CVE-2022-38181 Mali kernel exploit")
        var rooted = rootReady(rootsh)
        val recordedAttempt = AutoRootPreferences.currentExploitAttempt(ctx)
        if (!rooted && recordedAttempt != null) {
            log(
                "  recorded attempt ${recordedAttempt.status}: " +
                    "${recordedAttempt.detail}"
            )
            log("  probing the existing root hooks only; native rerun is blocked")
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            for (cpu in 0 until cpuCount) {
                if (rootReadyOnCpu(rootsh, cpu)) {
                    rooted = true
                    log("  recovered an existing root hook on cpu=$cpu")
                    break
                }
            }
            if (!rooted) {
                log("  [BLOCKED] This boot already consumed its Mali attempt.")
                log("  Reboot before another exploit attempt.")
                return Result(false, false, false, false, true)
            }
        }
        var memory = memoryState()
        var uptime = uptimeSeconds()
        val vmBefore = vmState()
        log("  preflight: free=${memory.freeKb}KiB available=${memory.availableKb}KiB " +
            "cached=${memory.cachedKb}KiB uptime=${"%.1f".format(uptime)}s")
        val execution = executionClass()
        log("  preflight: cgroup=${execution.first}")
        log("  cgroup: ${execution.second}")
        if (!rooted && !executionClassAllowed(executionMode, execution.first)) {
            log(
                "  [ERROR] ${executionMode.name.lowercase()} execution requires " +
                    if (executionMode == ExecutionMode.MANUAL) {
                        "the top-app cpuset."
                    } else {
                        "the foreground or top-app cpuset."
                    }
            )
            log("  Unlock the screen and keep SCRoot visible before continuing.")
            return Result(false, false, false, false, false)
        }
        if (!rooted && exploitWindowExpired(uptime)) {
            log("  [ERROR] The ${MAX_EXPLOIT_UPTIME_S.toInt()}s fresh-boot window has elapsed.")
            log("  Reboot the device before running the app again.")
            return Result(false, false, false, false, false)
        }

        if (!rooted && memory.availableKb < MIN_AVAILABLE_KB) {
            log("  [ERROR] MemAvailable is below the safety floor.")
            log("  Reboot the device, then open the app and try again.")
            return Result(false, false, false, false, false)
        }
        if (!rooted) {

            val quietDeadline = minOf(
                MAX_EXPLOIT_UPTIME_S,
                maxOf(TARGET_QUIET_UPTIME_S, uptime + QUIET_WAIT_EXTENSION_S)
            )
            if (!waitForQuietWindow(
                    ctx,
                    log,
                    MIN_EXPLOIT_UPTIME_S,
                    quietDeadline,
                    "early-boot",
                    warnAgainstInteraction = true
                )) {
                return Result(false, false, false, false, false)
            }
            log("  [READY] allocator stability gate passed")
            uptime = uptimeSeconds()
            memory = memoryState()
            log("  quiet-window: free=${memory.freeKb}KiB " +
                "available=${memory.availableKb}KiB uptime=${"%.1f".format(uptime)}s")
            if (memory.availableKb < MIN_AVAILABLE_KB) {
                log("  [ERROR] MemAvailable is below the safety floor after waiting.")
                return Result(false, false, false, false, false)
            }
        }
        if (!rooted && memory.freeKb in 1 until CONDITION_FREE_KB) {
            log("  allocator stabilization — 256MB reclaim precondition")
            val prepared = capture(memoryPrepare, "256", timeoutMs = 20_000)
            prepared.lines.filter { it.isNotBlank() }
                .forEach { log("  $it") }
            if (prepared.timedOut || prepared.rc != 0) {
                log("  [ERROR] memory precondition failed exit=${prepared.rc} timeout=${prepared.timedOut}")
                return Result(false, false, false, false, false)
            }
            Thread.sleep(2_000)
            memory = memoryState()
            log("  post-precondition: free=${memory.freeKb}KiB " +
                "available=${memory.availableKb}KiB")
            if (memory.freeKb in 1 until HARD_FREE_FLOOR_KB) {
                log("  [ERROR] MemFree did not recover above the measured 80MB safety floor.")
                log("  The exploit will not run. Reboot before trying again.")
                return Result(false, false, false, false, false)
            }
            val postPrepDeadline = minOf(
                MAX_EXPLOIT_UPTIME_S,
                uptimeSeconds() + POST_PREP_QUIET_TIMEOUT_S
            )
            if (!waitForQuietWindow(
                    ctx,
                    log,
                    uptimeSeconds(),
                    postPrepDeadline,
                    "post-precondition",
                    HARD_FREE_FLOOR_KB
                )) {
                return Result(false, false, false, false, false)
            }
            memory = memoryState()
        }
        uptime = uptimeSeconds()
        val finalExecution = executionClass()
        log("  final gate: cgroup=${finalExecution.first} uptime=${"%.1f".format(uptime)}s")
        log("  [vmstat] ${vmDelta(vmBefore, vmState())} reader=$lastVmReadDetail")
        if (!rooted && !executionClassAllowed(executionMode, finalExecution.first)) {
            log("  [ERROR] The app left the permitted cpuset while waiting.")
            return Result(false, false, false, false, false)
        }
        if (!rooted && exploitWindowExpired(uptime)) {
            log("  [ERROR] The fresh-boot window elapsed while waiting for the quiet window.")
            return Result(false, false, false, false, false)
        }
        if (!rooted && !nativeEntryMemoryIsSafe(memory)) {
            log(
                "  [ERROR] Final memory gate failed: free=${memory.freeKb}KiB " +
                    "available=${memory.availableKb}KiB."
            )
            return Result(false, false, false, false, false)
        }
        var tries = 0
        val safeTryLimit = maxExploitTries.coerceIn(0, 1)
        if (maxExploitTries > safeTryLimit) {
            log("  safety limit: exploit attempts are capped at one per boot")
        }
        val deadline = SystemClock.elapsedRealtime() + 6 * 60 * 1000L
        while (!rooted && tries < safeTryLimit && SystemClock.elapsedRealtime() < deadline) {

            if (rootReady(rootsh)) { rooted = true; break }
            val previousAttempt = AutoRootPreferences.currentExploitAttempt(ctx)
            if (previousAttempt != null) {
                log(
                    "  [BLOCKED] A Mali attempt is already recorded for this boot " +
                        "(${previousAttempt.status}: ${previousAttempt.detail})."
                )
                log("  Reboot before another exploit attempt.")
                return Result(false, false, false, false, true)
            }
            if (!AutoRootPreferences.claimExploitForCurrentBoot(ctx)) {
                log("  [BLOCKED] The per-boot exploit lock could not be claimed.")
                log("  Reboot before another exploit attempt.")
                return Result(false, false, false, false, true)
            }
            tries++
            log("  executing /dev/mali0 exploit — buffering trace to protect the allocator")
            log("  pausing screen and file writes for a 750ms settle...")
            Thread.sleep(750)
            val run = execBuffered(exploit, timeoutMs = 60_000, interruptible = false)

            log("  ── device-side trace (buffered) ──")
            run.lines.forEach { ui("  $it") }
            bootLogBatch(ctx, run.lines, "  ")
            log("  ── exit=${run.rc} timeout=${run.timedOut} ──")
            rooted = rootReady(rootsh)

            val completeWriteReceipt = run.lines.any {
                COMPLETE_WRITE_RECEIPT.matches(it.trim())
            }
            if (!rooted && (run.rc == 12 || completeWriteReceipt)) {
                val cpuCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                val graceMs = 30_000L
                val probeStarted = SystemClock.elapsedRealtime()
                val probeDeadline = probeStarted + graceMs
                var round = 0
                log(
                    "  payload write verified (exit=${run.rc}) — " +
                        "8MiB I-cache eviction + per-CPU hook verification " +
                        "(up to ${graceMs / 1000}s, no exploit rerun)"
                )

                Thread.sleep(500)
                probeLoop@ while (SystemClock.elapsedRealtime() < probeDeadline) {
                    round++
                    for (cpu in 0 until cpuCount) {
                        if (rootReadyOnCpu(rootsh, cpu)) {
                            rooted = true
                            val elapsed =
                                (SystemClock.elapsedRealtime() - probeStarted) / 1000.0
                            log(
                                "  hook verification succeeded round=$round cpu=$cpu " +
                                    "elapsed=${"%.1f".format(elapsed)}s"
                            )
                            break@probeLoop
                        }
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - probeStarted
                    if (round == 1 || round % 2 == 0) {
                        log(
                            "  hook verification in progress round=$round " +
                                "elapsed=${elapsedMs / 1000}s/${graceMs / 1000}s"
                        )
                    }
                    if (SystemClock.elapsedRealtime() < probeDeadline) {
                        Thread.sleep(500)
                    }
                }
                if (!rooted) {

                    rooted = rootReady(rootsh)
                }
                if (!rooted) {
                    log("  ${graceMs / 1000}s hook grace window expired")
                }
            }
            AutoRootPreferences.finishExploitForCurrentBoot(
                ctx,
                if (rooted) {
                    AutoRootPreferences.STATUS_SUCCESS
                } else {
                    AutoRootPreferences.STATUS_REBOOT_REQUIRED
                },
                "native_exit=${run.rc} timeout=${run.timedOut} root=$rooted"
            )
        }
        if (!rooted) {
            log("  [FAILED] kernel state is unsafe — retry blocked")
            return Result(false, false, false, false, true)
        }
        log("  [OK] root acquired")

        if (!isModuleLoaded()) {
            log("  app-domain bootstrap: direct finit_module call after root hook")
            val bootstrapAppId = ctx.applicationInfo.uid % 100_000
            val bootstrapRun = capture(
                bootstrap,
                "$nl/libksuglue.so",
                "enable_sucompat=1 enable_manager_fd=1 enable_setcon=1 " +
                    "bootstrap_appid=$bootstrapAppId",
                ksud,
                timeoutMs = 30_000
            )
            bootstrapRun.lines.filter { it.isNotBlank() }
                .forEach { log("  $it") }
            log("  bootstrap exit=${bootstrapRun.rc} timeout=${bootstrapRun.timedOut}")
            log("  bootstrap module=${if (isModuleLoaded()) 1 else 0}")
        }

        log("[2/3] module + ksud   [3/3] manager signature + crown")
        var bringup = ExecResult(-1, emptyList(), false)
        for (attempt in 1..3) {
            val script = bringupScript(
                nl,
                apk.absolutePath,
                meta.absolutePath,
                ctx.applicationInfo.uid
            )

            bringup = if (isModuleLoaded()) {
                execBuffered(
                    "/system/bin/su",
                    listOf("-c", script),
                    timeoutMs = 45_000
                )
            } else {
                capture(rootsh, script, timeoutMs = 45_000)
            }
            bringup.lines.filter { it.isNotBlank() }
                .forEach { log("  [bringup $attempt] $it") }
            val ksudReady = bringup.lines.any {
                it.contains("KSUD_RC=0") && it.contains("verify_rc=0")
            }
            if (!bringup.timedOut && ksudReady && isModuleLoaded() &&
                packageInfo(ctx, MANAGER_PKG) != null) break
            if (attempt < 3) {
                log("  retrying userspace bring-up ${attempt + 1}/3...")
            }
        }

        val mod = isModuleLoaded()
        val ksudReady = !bringup.timedOut && bringup.lines.any {
            it.contains("KSUD_RC=0") && it.contains("verify_rc=0")
        }
        val installedManager = verifyManager(ctx, apk, requireInstalled = true)
        log("  manager verify: ${installedManager.detail}")
        var mgr = false
        if (mod && installedManager.ok) {
            val appId = installedManager.uid % 100_000

            val launchCommand = if (launchManager) {
                "am start -n $MANAGER_PKG/.ui.MainActivity; LAUNCH=\$?"
            } else {
                "LAUNCH=0"
            }
            val crownScript =
                "echo $appId > /sys/module/ksu_glue/parameters/manager_appid; CROWN=\$?; " +
                    "RESTORE=skip; SELINUX=unknown; LAUNCH=skip; " +
                    "if [ \$CROWN -eq 0 ]; then " +
                    "am force-stop $MANAGER_PKG; " +
                    "echo 1 > /sys/module/ksu_glue/parameters/restore_sel_read_enforce; RESTORE=\$?; " +
                    "SELINUX=\$(cat /sys/fs/selinux/enforce 2>/dev/null); " +
                    "$launchCommand; fi; " +
                    "echo CROWN_RC=\$CROWN RESTORE_RC=\$RESTORE SELINUX=\$SELINUX LAUNCH_RC=\$LAUNCH APPID=$appId"
            val crown = execBuffered(
                "/system/bin/su",
                listOf("-c", crownScript),
                timeoutMs = 20_000
            )
            crown.lines.filter { it.isNotBlank() }.forEach { log("  $it") }
            mgr = !crown.timedOut && crown.rc == 0 &&
                crown.lines.any {
                    it.contains("CROWN_RC=0") &&
                        it.contains("RESTORE_RC=0") &&
                        it.contains("LAUNCH_RC=0") &&
                        it.contains("APPID=$appId")
                } && moduleManagerAppId() == appId
        }
        val systemUiIntegrated = if (mod && mgr && ksudReady) {
            provisionSystemUi(ctx, log)
        } else {
            log("[WARNING] Launcher integration deferred until KernelSU setup is healthy.")
            false
        }
        log(
            if (mod && systemUiIntegrated) {
                "[OK] complete (KernelSU + SCR-01 system UI integrated)"
            } else if (mod) {
                "[WARNING] KernelSU is live, but launcher integration needs repair"
            } else {
                "[WARNING] module not loaded — review the trace"
            }
        )
        return Result(
            rooted = true,
            moduleLoaded = mod,
            managerCrowned = mgr,
            userspaceReady = ksudReady,
            exploitAttempted = true,
            systemUiIntegrated = systemUiIntegrated
        )
    }

    private fun bringupScript(
        nl: String,
        apkPath: String,
        metaPath: String,
        bootstrapUid: Int
    ): String = buildString {
        val bootstrapAppId = bootstrapUid % 100_000
        append("set -x\n")
        append("NL='$nl'\n")
        append("APK='$apkPath'\n")
        append("META='$metaPath'\n")
        append("PKG=$MANAGER_PKG\n")
        append(metaModuleVerificationShell())
        append("echo ROOT_ENV uid=\$(id -u) context=\$(cat /proc/self/attr/current) seccomp=\$(awk '/Seccomp:/{print \$2}' /proc/self/status)\n")
        append("module_loaded() { awk '\$1==\"ksu_glue\"{found=1} END{exit !found}' /proc/modules; }\n")
        append("module_loaded || insmod \$NL/libksuglue.so enable_sucompat=1 enable_manager_fd=1 enable_setcon=1 bootstrap_appid=$bootstrapAppId\n")
        append("INSMOD_RC=\$?\n")
        append("module_loaded; MOD=\$?\n")
        append("echo INSMOD_RC=\$INSMOD_RC module=\$([ \$MOD -eq 0 ] && echo 1 || echo 0)\n")

        append("if [ \$MOD -eq 0 ]; then echo 1 > /sys/module/ksu_glue/parameters/restore_sel_read_enforce; EARLY_RESTORE_RC=\$?; else EARLY_RESTORE_RC=1; fi\n")
        append("echo EARLY_RESTORE_RC=\$EARLY_RESTORE_RC\n")
        append("[ \$MOD -eq 0 ] && [ \$EARLY_RESTORE_RC -eq 0 ] || exit 41\n")
        append("[ \$MOD -eq 0 ] && am force-stop \$PKG\n")

        append("INSTALLED_PATHS=\$(pm path \$PKG 2>/dev/null)\n")
        append("INSTALLED_COUNT=\$(printf '%s\\n' \"\$INSTALLED_PATHS\" | sed -n 's/^package://p' | awk 'END { print NR + 0 }')\n")
        append("case \"\$INSTALLED_COUNT\" in 1) INSTALLED_APK=\$(printf '%s\\n' \"\$INSTALLED_PATHS\" | sed -n 's/^package://p') ;; *) INSTALLED_APK= ;; esac\n")
        append("INSTALLED_HASH=\$(sha256sum \"\$INSTALLED_APK\" 2>/dev/null | awk '{print \$1}')\n")
        append("if [ \"\$INSTALLED_HASH\" != '${EXPECTED_ARTIFACT_SHA256["manager.apk"]}' ]; then pm install -r -g \"\$APK\"; INSTALL_RC=\$?; else INSTALL_RC=0; fi\n")
        append("if [ \$INSTALL_RC -eq 0 ]; then pm enable \$PKG >/dev/null; ENABLE_RC=\$?; else ENABLE_RC=1; fi\n")
        append("APP_UID=\$(pm list packages -U \$PKG | awk -v target=\"package:\$PKG\" '\$1 == target { sub(/^uid:/, \"\", \$2); print \$2; exit }')\n")
        append("case \$APP_UID in ''|*[!0-9]*) PRE_CROWN_RC=1 ;; *) APP_ID=\$((APP_UID % 100000)); echo \$APP_ID > /sys/module/ksu_glue/parameters/manager_appid; PRE_CROWN_RC=\$? ;; esac\n")
        append("echo PRE_CROWN_RC=\$PRE_CROWN_RC APP_UID=\${APP_UID:-missing} APP_ID=\${APP_ID:-missing}\n")
        append("[ \$INSTALL_RC -eq 0 ] && [ \$ENABLE_RC -eq 0 ] && [ \$PRE_CROWN_RC -eq 0 ] || exit 38\n")
        append("mkdir -p /data/adb\n")
        append("/data/adb/ksud -V >/dev/null\n")
        append("KSUD_RC=\$?\n")
        append("if [ \$KSUD_RC -eq 0 ]; then\n")
        append("  if meta_exact; then META_INSTALL_RC=0; else meta_not_blocked || exit 32; /data/adb/ksud module install \"\$META\"; META_INSTALL_RC=\$?; [ \$META_INSTALL_RC -ne 0 ] || meta_exact || META_INSTALL_RC=1; fi\n")
        append("  if [ \$META_INSTALL_RC -eq 0 ]; then\n")

        append("    \"\$NL/libksud.so\" late-load --allow-shell --package-name \"\$PKG\"\n")
        append("    LATE_LOAD_RC=\$?\n")
        append("    echo LATE_LOAD_RC=\$LATE_LOAD_RC\n")
        append("  else\n")
        append("    LATE_LOAD_RC=1\n")
        append("  fi\n")
        append("  [ \$LATE_LOAD_RC -eq 0 ] || exit 33\n")
        append("  sleep 2\n")

        append("  \"\$NL/libksud.so\" install --libadbroot \"\$NL/libadbroot.so\"\n")
        append("  ADB_INSTALL_RC=\$?\n")
        append("  /data/adb/ksud module metamodule >/dev/null && /data/adb/ksud module list >/dev/null\n")
        append("  MODULE_VERIFY_RC=\$?\n")
        append("  \"\$NL/libksucheck.so\" umount verify-global\n")
        append("  UMOUNT_VERIFY_RC=\$?\n")
        append("  \"\$NL/libksucheck.so\" check $bootstrapUid\n")
        append("  CAPABILITY_RC=\$?\n")
        append("  echo ADB_INSTALL_RC=\$ADB_INSTALL_RC MODULE_VERIFY_RC=\$MODULE_VERIFY_RC UMOUNT_VERIFY_RC=\$UMOUNT_VERIFY_RC CAPABILITY_RC=\$CAPABILITY_RC\n")
        append("  [ \$ADB_INSTALL_RC -eq 0 ] && [ \$MODULE_VERIFY_RC -eq 0 ] && [ \$UMOUNT_VERIFY_RC -eq 0 ] && [ \$CAPABILITY_RC -eq 0 ] && echo Y > /sys/module/ksu_glue/parameters/userspace_ready\n")
        append("fi\n")
        append("KSUD_VERIFY_RC=\$?\n")
        append("echo KSUD_RC=\$KSUD_RC verify_rc=\$KSUD_VERIFY_RC\n")
        append("pm path \$PKG >/dev/null 2>&1\n")
        append("INSTALL_RC=\$?\n")
        append("module_loaded; MOD=\$?\n")
        append("echo BRINGUP uid=\$(id -u) mod=\$([ \$MOD -eq 0 ] && echo 1 || echo 0) install_rc=\$INSTALL_RC\n")
    }
}
