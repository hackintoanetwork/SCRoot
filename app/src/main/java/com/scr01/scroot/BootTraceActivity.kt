package com.scr01.scroot

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetTextI18n")
class BootTraceActivity : Activity(), BootTraceBus.Listener {

    private data class Palette(
        val dark: Boolean,
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondary: Int,
        val divider: Int,
        val primary: Int,
        val primaryMuted: Int,
        val terminal: Int,
        val terminalStroke: Int,
        val terminalText: Int,
        val terminalMuted: Int,
        val terminalDivider: Int,
        val telemetry: Int,
        val payload: Int,
        val success: Int,
        val caution: Int,
        val warning: Int,
        val error: Int
    )

    private data class StageView(
        val stage: BootTraceBus.Stage,
        val bar: View,
        val label: TextView
    )

    private companion object {
        const val MAX_VISIBLE_CHARS = 112 * 1024
        const val MAX_PENDING_LINES = 1_200
        const val FLUSH_BATCH = 7
        const val FLUSH_DELAY_MS = 28L
        const val SUCCESS_HOLD_MS = 2_400L
    }

    private val palette: Palette by lazy(LazyThreadSafetyMode.NONE) {
        val dark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            Palette(
                dark = true,
                background = Color.parseColor("#0B0C0E"),
                surface = Color.parseColor("#17191C"),
                text = Color.parseColor("#F3F4F6"),
                secondary = Color.parseColor("#989DA6"),
                divider = Color.parseColor("#2B2E33"),
                primary = Color.parseColor("#65AEFF"),
                primaryMuted = Color.parseColor("#243B52"),
                terminal = Color.parseColor("#07080A"),
                terminalStroke = Color.parseColor("#292C31"),
                terminalText = Color.WHITE,
                terminalMuted = Color.WHITE,
                terminalDivider = Color.parseColor("#292D33"),
                telemetry = Color.parseColor("#89DDFF"),
                payload = Color.parseColor("#C792EA"),
                success = Color.parseColor("#82C7FF"),
                caution = Color.parseColor("#FF8A80"),
                warning = Color.parseColor("#FFB454"),
                error = Color.parseColor("#FF6B6B")
            )
        } else {
            Palette(
                dark = false,
                background = Color.parseColor("#F6F6F6"),
                surface = Color.parseColor("#FFFFFF"),
                text = Color.parseColor("#17191C"),
                secondary = Color.parseColor("#656A72"),
                divider = Color.parseColor("#E3E6EA"),
                primary = Color.parseColor("#0072DE"),
                primaryMuted = Color.parseColor("#DCEEFF"),
                terminal = Color.parseColor("#FFFFFF"),
                terminalStroke = Color.parseColor("#E3E6EA"),
                terminalText = Color.BLACK,
                terminalMuted = Color.BLACK,
                terminalDivider = Color.parseColor("#E3E6EA"),
                telemetry = Color.parseColor("#006B8F"),
                payload = Color.parseColor("#71408F"),
                success = Color.parseColor("#006FB9"),
                caution = Color.parseColor("#BA1A1A"),
                warning = Color.parseColor("#835300"),
                error = Color.parseColor("#B3261E")
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<BootTraceBus.Line>()
    private val incoming = ArrayDeque<BootTraceBus.Line>()
    private val incomingLock = Any()
    private val incomingScheduled = AtomicBoolean(false)
    private val stageViews = ArrayList<StageView>()
    private val monospace = Typeface.create("monospace", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private lateinit var screen: LinearLayout
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var terminal: TextView
    private lateinit var elapsed: TextView
    private lateinit var close: TextView
    private var startedAt = 0L
    private var loadedGeneration = -1L
    private var currentStage = BootTraceBus.Stage.BOOT
    private var runState = BootTraceBus.RunState.IDLE
    private var flushScheduled = false
    private var dismissScheduled = false
    private var activityAlive = true
    private var focusRecorded = false
    private var minimumUptimeDeadlineElapsedMs: Long? = null
    @Volatile private var acceptingTraceEvents = false

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushScheduled = false
            flushPending()
        }
    }

    private val traceDispatchRunnable = Runnable { drainTraceEvents() }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!activityAlive || !::elapsed.isInitialized) return
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L)
                .coerceAtLeast(0L)
            elapsed.text = String.format(
                Locale.US,
                "%02d:%02d",
                seconds / 60L,
                seconds % 60L
            )
            refreshMinimumUptimeCountdown()
            if (runState == BootTraceBus.RunState.RUNNING) {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val dismissRunnable = Runnable {
        if (!activityAlive || isFinishing || isDestroyed) return@Runnable
        screen.animate()
            .alpha(0f)
            .setDuration(320L)
            .withEndAction {
                if (activityAlive && !isFinishing && !isDestroyed) finishAndRemoveTask()
            }
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(
        color: Int,
        radius: Int,
        stroke: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int = palette.text,
        font: Typeface = regular
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = font
        includeFontPadding = false
    }

    private fun spacer(height: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val createCount = AutoRootPreferences.recordTraceCreated(this)
        Log.i(
            "SCRootTrace",
            "[created] count=$createCount"
        )
        AutoRootService.acknowledgeTracePresented(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(24), dp(16), dp(24), dp(18))
            alpha = 1f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            addView(label(
                "SCRoot",
                25f,
                palette.text,
                Typeface.create("sans-serif", Typeface.BOLD)
            ))
            addView(label(
                "AUTOMATIC BOOT ROOT  /  SCR-01",
                9.5f,
                palette.secondary,
                monospace
            ).apply {
                letterSpacing = 0.06f
                setPadding(0, dp(5), 0, 0)
            })
        })
        close = label("Close", 12f, palette.primary, medium).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { finishAndRemoveTask() }
        }
        header.addView(close)
        screen.addView(header)
        screen.addView(spacer(22))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        status = label("PREPARING", 13f, palette.primary, medium).apply {
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        elapsed = label("00:00", 11f, palette.secondary, monospace)
        statusRow.addView(status)
        statusRow.addView(elapsed)
        screen.addView(statusRow)
        statusDetail = label(
            "Waiting for the device safety gate",
            12f,
            palette.secondary
        ).apply {
            setPadding(0, dp(6), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        screen.addView(statusDetail)
        screen.addView(spacer(18))

        screen.addView(createStageStrip())
        screen.addView(spacer(16))

        val terminalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(
                palette.terminal,
                20,
                palette.terminalStroke.takeUnless { it == Color.TRANSPARENT }
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        terminalCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(
                "EXPLOIT TRACE",
                10f,
                palette.terminalMuted,
                monospace
            ).apply {
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            addView(label(
                "LIVE",
                9f,
                palette.telemetry,
                monospace
            ).apply {
                letterSpacing = 0.08f
            })
        })
        terminalCard.addView(View(this).apply {
            setBackgroundColor(palette.terminalDivider)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).also {
                it.topMargin = dp(12)
                it.bottomMargin = dp(12)
            }
        })
        terminal = TextView(this).apply {
            textSize = 9.5f
            typeface = monospace
            includeFontPadding = false
            setTextColor(palette.terminalText)
            setLineSpacing(dp(2).toFloat(), 1f)
            gravity = Gravity.TOP or Gravity.START
            movementMethod = ScrollingMovementMethod()
            setHorizontallyScrolling(true)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        terminalCard.addView(terminal)
        screen.addView(terminalCard)

        setContentView(screen)
        configureSystemBars()
        renderStage(BootTraceBus.Stage.BOOT)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (runState == BootTraceBus.RunState.RUNNING) {
            setTraceNavigationHidden(true)
        }
        if (focusRecorded) return
        focusRecorded = true
        val visibleCount = AutoRootPreferences.recordTraceVisible(this)
        Log.i(
            "SCRootTrace",
            "[visible] count=$visibleCount"
        )
    }

    private fun createStageStrip(): LinearLayout {
        val stages = listOf(
            BootTraceBus.Stage.MEMORY to "MEMORY",
            BootTraceBus.Stage.UAF to "UAF",
            BootTraceBus.Stage.PGD to "PGD",
            BootTraceBus.Stage.PATCH to "PATCH",
            BootTraceBus.Stage.KSU to "KSU"
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            stages.forEachIndexed { index, pair ->
                addView(LinearLayout(this@BootTraceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).also {
                        if (index > 0) it.marginStart = dp(5)
                    }
                    val bar = View(this@BootTraceActivity).apply {
                        background = rounded(palette.divider, 2)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(3)
                        )
                    }
                    val stageLabel = label(
                        pair.second,
                        8.5f,
                        palette.secondary,
                        monospace
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        letterSpacing = 0.05f
                        setPadding(0, dp(7), 0, 0)
                    }
                    stageViews.add(StageView(pair.first, bar, stageLabel))
                    addView(bar)
                    addView(stageLabel)
                })
            }
        }
    }

    private fun configureSystemBars() {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val appearance = if (palette.dark) 0 else mask
        try {
            window.insetsController?.setSystemBarsAppearance(appearance, mask)
        } catch (_: RuntimeException) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (palette.dark) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun setTraceNavigationHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (hidden) {
                    controller.hide(WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.navigationBars())
                }
            }
            return
        }

        @Suppress("DEPRECATION")
        val lightBars = if (palette.dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (hidden) {
            lightBars or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            lightBars
        }
    }

    override fun onStart() {
        super.onStart()
        acceptingTraceEvents = true
        val snapshot = BootTraceBus.register(this)

        applySnapshot(snapshot, force = true)
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    override fun onStop() {
        acceptingTraceEvents = false
        BootTraceBus.unregister(this)
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(traceDispatchRunnable)
        synchronized(incomingLock) { incoming.clear() }
        incomingScheduled.set(false)
        flushScheduled = false
        super.onStop()
    }

    override fun onBackPressed() {
        if (runState == BootTraceBus.RunState.RUNNING) {
            moveTaskToBack(true)
        } else {
            finishAndRemoveTask()
        }
    }

    override fun onTraceReset(snapshot: BootTraceBus.Snapshot) {
        runOnUiThread {
            if (!acceptingTraceEvents || !activityAlive || isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            applySnapshot(snapshot, force = true)
        }
    }

    override fun onTraceLine(line: BootTraceBus.Line) {
        if (!acceptingTraceEvents || !activityAlive) return
        synchronized(incomingLock) {
            if (!acceptingTraceEvents || !activityAlive) return
            while (incoming.size >= MAX_PENDING_LINES) incoming.removeFirst()
            incoming.addLast(line)
        }
        scheduleTraceDispatch()
    }

    private fun scheduleTraceDispatch() {
        if (!incomingScheduled.compareAndSet(false, true)) return
        if (!handler.post(traceDispatchRunnable)) incomingScheduled.set(false)
    }

    private fun drainTraceEvents() {
        if (!acceptingTraceEvents || !activityAlive || isFinishing || isDestroyed) {
            synchronized(incomingLock) { incoming.clear() }
            incomingScheduled.set(false)
            return
        }
        val batch = ArrayList<BootTraceBus.Line>(MAX_PENDING_LINES)
        synchronized(incomingLock) {
            while (incoming.isNotEmpty()) batch.add(incoming.removeFirst())
        }
        batch.forEach(::enqueue)
        batch.maxByOrNull { it.stage.ordinal }?.let { latest ->
            renderStage(latest.stage)
            statusDetail.text = detailForStage(latest.stage)
        }
        incomingScheduled.set(false)
        val hasMore = synchronized(incomingLock) { incoming.isNotEmpty() }
        if (hasMore) scheduleTraceDispatch()
    }

    override fun onTraceFinished(state: BootTraceBus.RunState, detail: String) {
        runOnUiThread {
            if (!acceptingTraceEvents || !activityAlive || isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            drainTraceEvents()
            renderCompletion(state, detail)
        }
    }

    private fun applySnapshot(snapshot: BootTraceBus.Snapshot, force: Boolean = false) {
        if (!force && loadedGeneration == snapshot.generation) {
            renderStage(snapshot.stage)
            if (snapshot.state != BootTraceBus.RunState.RUNNING) {
                renderCompletion(snapshot.state, snapshot.detail)
            }
            return
        }
        loadedGeneration = snapshot.generation
        startedAt = snapshot.startedAtElapsedMs
            .takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        runState = snapshot.state
        currentStage = BootTraceBus.Stage.BOOT
        minimumUptimeDeadlineElapsedMs = null
        pending.clear()
        terminal.text = ""
        dismissScheduled = false
        handler.removeCallbacks(dismissRunnable)
        screen.alpha = 1f

        enqueue(
            BootTraceBus.Line(
                "SCR-01  /  SCR01KDU1AVK2",
                BootTraceBus.Tone.MUTED,
                BootTraceBus.Stage.BOOT
            )
        )
        enqueue(
            BootTraceBus.Line(
                "CVE-2022-38181  ·  Mali-G57 Valhall r25p0",
                BootTraceBus.Tone.MUTED,
                BootTraceBus.Stage.BOOT
            )
        )
        enqueue(
            BootTraceBus.Line(
                "────────────────────────────────────────",
                BootTraceBus.Tone.MUTED,
                BootTraceBus.Stage.BOOT
            )
        )
        snapshot.lines.forEach(::enqueue)
        renderStage(snapshot.stage)
        statusDetail.text = snapshot.detail.ifBlank { detailForStage(snapshot.stage) }
        if (snapshot.state != BootTraceBus.RunState.RUNNING) {
            renderCompletion(snapshot.state, snapshot.detail)
        } else {
            setTraceNavigationHidden(true)
            status.setTextColor(palette.primary)
            close.visibility = View.GONE
        }
    }

    private fun enqueue(line: BootTraceBus.Line) {
        line.countdownDeadlineElapsedMs?.let {
            minimumUptimeDeadlineElapsedMs = it
        }
        while (pending.size >= MAX_PENDING_LINES) pending.removeFirst()
        pending.addLast(line)
        if (!flushScheduled) {
            flushScheduled = true
            handler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
        }
    }

    private fun flushPending() {
        if (!activityAlive || !::terminal.isInitialized) return
        val rendered = SpannableStringBuilder()
        repeat(minOf(FLUSH_BATCH, pending.size)) {
            val line = pending.removeFirst()
            val start = rendered.length
            rendered.append(line.text).append('\n')
            rendered.setSpan(
                ForegroundColorSpan(colorFor(line.tone)),
                start,
                rendered.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (rendered.isNotEmpty()) terminal.append(rendered)
        refreshMinimumUptimeCountdown()
        val visible = terminal.text
        if (visible is SpannableStringBuilder && visible.length > MAX_VISIBLE_CHARS) {
            val target = visible.length - MAX_VISIBLE_CHARS
            val boundary = TextUtils.indexOf(visible, '\n', target)
            visible.delete(0, if (boundary >= 0) boundary + 1 else target)
        }
        terminal.layout?.let { layout ->
            val amount = layout.getLineTop(terminal.lineCount) - terminal.height
            terminal.scrollTo(0, amount.coerceAtLeast(0))
        }
        if (pending.isNotEmpty()) {
            flushScheduled = true
            handler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
        } else if (runState == BootTraceBus.RunState.SUCCESS) {
            scheduleSuccessDismiss()
        }
    }

    private fun colorFor(tone: BootTraceBus.Tone): Int = when (tone) {
        BootTraceBus.Tone.DEFAULT -> palette.terminalText
        BootTraceBus.Tone.MUTED -> palette.terminalMuted
        BootTraceBus.Tone.TELEMETRY -> palette.telemetry
        BootTraceBus.Tone.PAYLOAD -> palette.payload
        BootTraceBus.Tone.SUCCESS -> palette.success
        BootTraceBus.Tone.CAUTION -> palette.caution
        BootTraceBus.Tone.WARNING -> palette.warning
        BootTraceBus.Tone.ERROR -> palette.error
    }

    private fun refreshMinimumUptimeCountdown() {
        val deadline = minimumUptimeDeadlineElapsedMs ?: return
        if (!::terminal.isInitialized) return
        val remainingMs = deadline - SystemClock.elapsedRealtime()
        val replacement = BootTracePresentation.minimumUptimeCountdownText(remainingMs)
        replaceMinimumUptimeLine(replacement, palette.caution)
    }

    private fun replaceMinimumUptimeLine(replacement: String, color: Int) {
        val visible = terminal.text as? SpannableStringBuilder ?: return
        val start = TextUtils.indexOf(visible, BootTracePresentation.MINIMUM_UPTIME_PREFIX)
        if (start < 0) return
        val newline = TextUtils.indexOf(visible, '\n', start)
        val end = if (newline >= 0) newline else visible.length
        if (visible.subSequence(start, end).toString() == replacement) return
        visible.getSpans(start, end, ForegroundColorSpan::class.java)
            .forEach(visible::removeSpan)
        visible.replace(start, end, replacement)
        visible.setSpan(
            ForegroundColorSpan(color),
            start,
            start + replacement.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun renderStage(next: BootTraceBus.Stage) {
        if (next.ordinal > currentStage.ordinal) currentStage = next
        stageViews.forEach { item ->
            val reached = currentStage == BootTraceBus.Stage.DONE ||
                item.stage.ordinal <= currentStage.ordinal
            val active = currentStage != BootTraceBus.Stage.DONE &&
                item.stage == currentStage
            item.bar.background = rounded(
                if (reached) palette.primary else palette.divider,
                2
            )
            item.label.setTextColor(
                when {
                    active -> palette.primary
                    reached -> palette.text
                    else -> palette.secondary
                }
            )
            item.label.alpha = if (reached) 1f else 0.55f
        }
        if (runState == BootTraceBus.RunState.RUNNING) {
            status.text = when (currentStage) {
                BootTraceBus.Stage.BOOT -> "PREPARING"
                BootTraceBus.Stage.MEMORY -> "MEMORY GROOMING"
                BootTraceBus.Stage.UAF -> "UAF RECLAIM"
                BootTraceBus.Stage.PGD -> "PAGE TABLE CONTROL"
                BootTraceBus.Stage.PATCH -> "KERNEL PATCH"
                BootTraceBus.Stage.KSU -> "KERNELSU SETUP"
                BootTraceBus.Stage.DONE -> "FINALIZING"
            }
        }
    }

    private fun detailForStage(stage: BootTraceBus.Stage): String = when (stage) {
        BootTraceBus.Stage.BOOT -> "Waiting for the device safety gate"
        BootTraceBus.Stage.MEMORY -> "Preparing a stable Mali allocator layout"
        BootTraceBus.Stage.UAF -> "Reclaiming the stale JIT allocation"
        BootTraceBus.Stage.PGD -> "Building the physical write primitive"
        BootTraceBus.Stage.PATCH -> "Applying the verified kernel patch"
        BootTraceBus.Stage.KSU -> "Loading and verifying KernelSU Next"
        BootTraceBus.Stage.DONE -> "Root and KernelSU are ready"
    }

    private fun renderCompletion(state: BootTraceBus.RunState, detail: String) {
        runState = state
        handler.removeCallbacks(timerRunnable)
        if (state != BootTraceBus.RunState.RUNNING) {
            setTraceNavigationHidden(false)
        }
        when (state) {
            BootTraceBus.RunState.SUCCESS -> {
                renderStage(BootTraceBus.Stage.DONE)
                status.text = "ROOT READY"
                status.setTextColor(palette.primary)
                statusDetail.text = "Root and KernelSU are ready"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (pending.isEmpty()) scheduleSuccessDismiss()
            }
            BootTraceBus.RunState.FAILURE -> {
                status.text = "SETUP STOPPED"
                status.setTextColor(palette.error)
                statusDetail.text = detail.ifBlank {
                    "Review the trace before rebooting"
                }
                close.visibility = View.VISIBLE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            BootTraceBus.RunState.IDLE -> {
                status.text = "WAITING"
                statusDetail.text = "Waiting for automatic root"
            }
            BootTraceBus.RunState.RUNNING -> Unit
        }
    }

    private fun scheduleSuccessDismiss() {
        if (dismissScheduled || runState != BootTraceBus.RunState.SUCCESS) return
        dismissScheduled = true
        handler.postDelayed(dismissRunnable, SUCCESS_HOLD_MS)
    }

    override fun onDestroy() {
        activityAlive = false
        acceptingTraceEvents = false
        setTraceNavigationHidden(false)
        BootTraceBus.unregister(this)
        handler.removeCallbacksAndMessages(null)
        if (::screen.isInitialized) screen.animate().cancel()
        synchronized(incomingLock) { incoming.clear() }
        incomingScheduled.set(false)
        pending.clear()
        super.onDestroy()
    }
}
