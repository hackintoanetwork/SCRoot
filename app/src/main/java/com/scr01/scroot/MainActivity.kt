package com.scr01.scroot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal fun pipelineIsActive(
    rootFlowRunning: Boolean,
    autoServiceActive: Boolean,
    manualGuardActive: Boolean = false
): Boolean = rootFlowRunning || autoServiceActive || manualGuardActive

internal fun manualExploitAttemptLimit(moduleLive: Boolean): Int =
    if (moduleLive) 0 else 1

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private companion object {
        const val RESEARCH_URL = "https://hackintoanetwork.com/"
        const val MAX_VISIBLE_LOG_CHARS = 160 * 1024
        const val MAX_PENDING_LOG_LINES = 2_000
        const val MAX_PENDING_LOG_CHARS = 512 * 1024
        const val MAX_LOG_LINE_CHARS = 4_096
        const val LOG_FLUSH_BATCH = 128
        const val LOG_FLUSH_DELAY_MS = 32L
        const val ALLOCATOR_CAUTION_RAW = "[CAUTION] allocator stability gate active"
        const val ALLOCATOR_CAUTION_DISPLAY = "[WAIT] Please wait. Do not touch the screen."
        const val ALLOCATOR_READY_RAW = "[READY] allocator stability gate passed"
        const val ALLOCATOR_READY_DISPLAY = "[READY] Memory stability confirmed"
        const val AUTO_ROOT_RESTART_MESSAGE =
            "Restart the device. Auto root will start after reboot."

        fun runManualFlow(
            appContext: android.content.Context,
            owner: WeakReference<MainActivity>,
            maxExploitTries: Int
        ) {
            try {
                owner.get()?.logln("")
                val result = RootFlow.run(
                    appContext,
                    maxExploitTries = maxExploitTries,
                    launchManager = false,
                    executionMode = RootFlow.ExecutionMode.MANUAL
                ) { line ->
                    owner.get()?.let { activity ->
                        try {
                            activity.logln(line)
                            activity.updateProgressFromLog(line)
                        } catch (_: RuntimeException) {
                        }
                    }
                }

                owner.get()?.let { activity ->
                    activity.logln("")
                    val complete = result.rooted &&
                        result.moduleLoaded &&
                        result.managerCrowned &&
                        result.userspaceReady &&
                        result.systemUiIntegrated
                    when {
                        complete -> {
                            activity.logln("[OK] KernelSU-Next setup is complete.")
                            activity.logln("Confirm the active status in KernelSU Manager.")
                            activity.finishUi(true)
                        }
                        !result.rooted && result.exploitAttempted -> {
                            activity.logln("[FAILED] The kernel exploit did not complete.")
                            activity.logln("[CAUTION] Retrying in the same boot may cause a kernel panic.")
                            activity.logln("Reboot the device before opening SCRoot again.")
                            activity.finishReboot()
                        }
                        !result.rooted -> {
                            activity.logln("[BLOCKED] The safety preflight stopped execution.")
                            activity.logln("The native exploit was not executed.")
                            activity.finishUi(false, "Check conditions again")
                        }
                        !result.moduleLoaded -> {
                            activity.logln("[FAILED] Root was acquired, but the kernel module did not load.")
                            activity.logln("[CAUTION] A temporary kernel hook may still be active.")
                            activity.logln("Reboot the device before opening SCRoot again.")
                            activity.finishReboot()
                        }
                        !result.userspaceReady -> {
                            activity.logln("[FAILED] The module loaded, but ksud verification did not complete.")
                            activity.logln("Userspace setup can be retried without repeating the exploit.")
                            activity.finishUi(false, "Retry setup repair")
                        }
                        !result.systemUiIntegrated -> {
                            activity.logln("[FAILED] KernelSU is ready, but the SCR-01 launcher integration did not complete.")
                            activity.logln("The Apps screen, Root menu and native Recents can be repaired without repeating the exploit.")
                            activity.finishUi(false, "Repair launcher integration")
                        }
                        else -> {
                            activity.logln("[FAILED] Manager signature verification or crown setup did not complete.")
                            activity.finishUi(false, "Retry manager connection")
                        }
                    }
                }
            } catch (error: Exception) {
                owner.get()?.let { activity ->
                    activity.logln("[ERROR] ${error.message ?: error.javaClass.simpleName}")
                    activity.finishAfterUnexpectedFailure()
                }
            }
        }

    }

    private data class ExistingKsuState(
        val moduleLive: Boolean,
        val userspaceReady: Boolean,
        val managerCrowned: Boolean,
        val systemUiIntegrated: Boolean
    )

    private data class UiPalette(
        val dark: Boolean,
        val background: Int,
        val surface: Int,
        val surfaceMuted: Int,
        val primary: Int,
        val primaryAction: Int,
        val primaryContainer: Int,
        val success: Int,
        val successAction: Int,
        val successContainer: Int,
        val warning: Int,
        val warningContainer: Int,
        val danger: Int,
        val dangerAction: Int,
        val dangerContainer: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val divider: Int,
        val switchThumbOff: Int,
        val switchTrackOn: Int,
        val switchTrackOff: Int,
        val logSurface: Int,
        val logStroke: Int
    )

    private lateinit var log: TextView
    private lateinit var logState: TextView
    private lateinit var button: Button
    private lateinit var overallState: TextView
    private lateinit var setupSummary: TextView
    private lateinit var autoRootSwitch: Switch
    private lateinit var autoRootSummary: TextView
    private var running = false
    private var logAutoFollow = true
    private var logTouchY = 0f
    private var openManagerOnClick = false
    private var enableAutoRootOnClick = false
    private var autoRootDangerState = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SCRoot-State")
    }
    private val stateRefreshGeneration = AtomicInteger(0)
    private val stateRefreshLock = Any()
    private var stateRefreshWorkerScheduled = false
    private var stateRefreshRequested = false
    private val pendingLogLines = ArrayDeque<String>()
    private val pendingLogLock = Any()
    private var pendingLogChars = 0
    private val logFlushScheduled = AtomicBoolean(false)
    @Volatile private var activityAlive = true
    @Volatile private var minimumUptimeDeadlineElapsedMs: Long? = null
    private val flushLogRunnable = Runnable { flushPendingLogs() }
    private val minimumUptimeCountdownRunnable = object : Runnable {
        override fun run() {
            if (!activityAlive || minimumUptimeDeadlineElapsedMs == null) return
            refreshMinimumUptimeCountdown()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private fun stopMinimumUptimeCountdown() {
        minimumUptimeDeadlineElapsedMs = null
        mainHandler.removeCallbacks(minimumUptimeCountdownRunnable)
    }
    private var pipelineWasObserved = false
    private var changingAutoRootSwitch = false
    private var lastStateLogKey: String? = null
    private val pipelinePollRunnable = object : Runnable {
        override fun run() {
            if (!activityAlive) return
            refreshMinimumUptimeCountdown()
            if (pipelineIsActive(
                    rootFlowRunning = RootFlow.isRunning(),
                    autoServiceActive = AutoRootService.isActiveInProcess(),
                    manualGuardActive = ManualFlowGuardService.isActiveInProcess()
                )) {
                pipelineWasObserved = true
                mainHandler.postDelayed(this, 500L)
                return
            }
            if (pipelineWasObserved) {
                pipelineWasObserved = false
                running = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refreshExistingStateAsync()
                refreshAutoRootSummary()
            }
        }
    }

    private val palette: UiPalette by lazy(LazyThreadSafetyMode.NONE) {
        val dark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            UiPalette(
                dark = true,
                background = Color.parseColor("#0B0C0E"),
                surface = Color.parseColor("#191A1D"),
                surfaceMuted = Color.parseColor("#27292D"),
                primary = Color.parseColor("#65AEFF"),
                primaryAction = Color.parseColor("#286FB8"),
                primaryContainer = Color.parseColor("#172B40"),
                success = Color.parseColor("#72BFFF"),
                successAction = Color.parseColor("#176DB3"),
                successContainer = Color.parseColor("#172C40"),
                warning = Color.parseColor("#F2BE67"),
                warningContainer = Color.parseColor("#342813"),
                danger = Color.parseColor("#FF8181"),
                dangerAction = Color.parseColor("#B9474D"),
                dangerContainer = Color.parseColor("#351C1F"),
                textPrimary = Color.parseColor("#F3F4F6"),
                textSecondary = Color.parseColor("#A8ACB3"),
                divider = Color.parseColor("#303238"),
                switchThumbOff = Color.parseColor("#D8DADF"),
                switchTrackOn = Color.parseColor("#315E82"),
                switchTrackOff = Color.parseColor("#55585F"),
                logSurface = Color.parseColor("#07080A"),
                logStroke = Color.parseColor("#292C31")
            )
        } else {
            UiPalette(
                dark = false,
                background = Color.parseColor("#F6F6F6"),
                surface = Color.parseColor("#FCFCFC"),
                surfaceMuted = Color.parseColor("#EFF0F2"),
                primary = Color.parseColor("#0072DE"),
                primaryAction = Color.parseColor("#0072DE"),
                primaryContainer = Color.parseColor("#F7FBFF"),
                success = Color.parseColor("#0072DE"),
                successAction = Color.parseColor("#0072DE"),
                successContainer = Color.parseColor("#F2F8FF"),
                warning = Color.parseColor("#9A6200"),
                warningContainer = Color.parseColor("#FFF4D9"),
                danger = Color.parseColor("#C83B3B"),
                dangerAction = Color.parseColor("#C83B3B"),
                dangerContainer = Color.parseColor("#FFF7F7"),
                textPrimary = Color.parseColor("#17191C"),
                textSecondary = Color.parseColor("#656A72"),
                divider = Color.parseColor("#E6E8EB"),
                switchThumbOff = Color.WHITE,
                switchTrackOn = Color.parseColor("#A7D2F7"),
                switchTrackOff = Color.parseColor("#C8CBD0"),
                logSurface = Color.parseColor("#111318"),
                logStroke = Color.TRANSPARENT
            )
        }
    }
    private val background get() = palette.background
    private val surface get() = palette.surface
    private val surfaceMuted get() = palette.surfaceMuted
    private val primary get() = palette.primary
    private val primaryContainer get() = palette.primaryContainer
    private val success get() = palette.success
    private val successContainer get() = palette.successContainer
    private val warning get() = palette.warning
    private val warningContainer get() = palette.warningContainer
    private val danger get() = palette.danger
    private val dangerContainer get() = palette.dangerContainer
    private val textPrimary get() = palette.textPrimary
    private val textSecondary get() = palette.textSecondary
    private val divider get() = palette.divider

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val monospace = Typeface.create("monospace", Typeface.NORMAL)
    private val logErrorColor = Color.parseColor("#FF6B6B")
    private val logWarningColor = Color.parseColor("#FFB454")
    private val logOkColor = Color.parseColor("#82C7FF")
    private val logStageColor = Color.parseColor("#82AAFF")
    private val logPayloadColor = Color.parseColor("#C792EA")
    private val logTelemetryColor = Color.parseColor("#89DDFF")
    private val logDefaultColor = Color.parseColor("#D8DEE9")
    private val logoTypeface: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(strokeWidth), it) }
    }

    private fun textView(
        text: CharSequence,
        size: Float,
        color: Int = textPrimary,
        typeface: Typeface = regular
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        this.typeface = typeface
        includeFontPadding = false
    }

    private fun spacer(height: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height)
        )
    }

    private fun cardLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 24)
        elevation = 0f
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun sectionTitle(title: String, description: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView(title, 19f, textPrimary, regular))
            if (description != null) {
                addView(textView(description, 13f, textSecondary).apply {
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(0f, 1.12f)
                })
            }
        }

    private fun pill(
        label: String,
        foreground: Int,
        container: Int
    ): TextView = textView(label, 13f, foreground, medium).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(container, 20)
    }

    private fun deviceMetric(label: String, value: String, weight: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
            )
            addView(textView(label, 12f, textSecondary, medium).apply {
                maxLines = 1
            })
            addView(textView(value, 13f, textPrimary, regular).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                contentDescription = "$label, $value"
                setPadding(0, dp(6), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

    private fun wideDeviceMetric(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(8), dp(3), dp(8), dp(3))
            addView(textView(label, 12f, textSecondary, medium).apply {
                maxLines = 1
            })
            addView(textView(value, 13f, textPrimary, regular).apply {
                maxLines = 2
                setHorizontallyScrolling(false)
                contentDescription = "$label, $value"
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun dividerView(): View = View(this).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(24), 0, dp(24), dp(32))
        }

        val appHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(16), 0, dp(14))
            addView(textView("SCRoot", 27f, textPrimary, logoTypeface))
            addView(textView(
                "Root access for Galaxy SCR-01",
                12f,
                textSecondary
            ).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        root.addView(appHeader)

        val statusCard = cardLayout()
        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusHeader.addView(textView("Status", 18f, textPrimary, regular).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        overallState = pill("Checking", primary, primaryContainer)
        statusHeader.addView(overallState)
        statusCard.addView(statusHeader)
        setupSummary = textView(
            "Checking the device status.",
            13f,
            textSecondary
        ).apply {
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.12f)
        }
        statusCard.addView(setupSummary)
        val statusActionHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        statusCard.addView(statusActionHost)
        root.addView(statusCard)
        root.addView(spacer(12))

        val infoCard = cardLayout()
        infoCard.addView(sectionTitle("Device"))
        infoCard.addView(spacer(12))
        infoCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(deviceMetric("Model", Build.MODEL, 1f))
            addView(deviceMetric(
                "Kernel",
                System.getProperty("os.version")
                    ?.substringBefore("-")
                    ?: "Unavailable",
                1f
            ))
        })
        infoCard.addView(spacer(10))
        infoCard.addView(wideDeviceMetric(
            "Firmware",
            Build.DISPLAY.ifBlank { Build.ID }
        ))
        root.addView(infoCard)
        root.addView(spacer(12))

        val automationCard = cardLayout()
        val automationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
        }
        automationRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).also { it.marginEnd = dp(14) }
            addView(textView("Run at boot", 18f, textPrimary, regular))
            addView(textView("Auto root after reboot", 13f, textSecondary).apply {
                setPadding(0, dp(5), 0, 0)
            })
        })
        autoRootSwitch = Switch(this).apply {
            isChecked = AutoRootPreferences.isEnabled(this@MainActivity)
            showText = false
            splitTrack = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(success, palette.switchThumbOff)
            )
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(palette.switchTrackOn, palette.switchTrackOff)
            )
            contentDescription = "Automatic root at boot"
            minWidth = dp(52)
            minimumWidth = dp(52)
        }
        automationRow.addView(autoRootSwitch)
        automationCard.addView(automationRow)
        autoRootSummary = textView("", 13f, textSecondary).apply {
            setPadding(0, dp(12), 0, 0)
        }
        automationCard.addView(autoRootSummary)
        autoRootSwitch.setOnCheckedChangeListener { _, checked ->
            if (changingAutoRootSwitch) return@setOnCheckedChangeListener
            if (!AutoRootPreferences.setEnabled(this, checked)) {
                changingAutoRootSwitch = true
                autoRootSwitch.isChecked = !checked
                changingAutoRootSwitch = false
                Toast.makeText(
                    this,
                    "The boot setting could not be saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshAutoRootSummary()
            if (RootFlow.currentExploitWindowExpired() && !RootFlow.isModuleLoaded()) {
                showFreshBootWindowExpired()
            }
        }
        automationRow.setOnClickListener {
            autoRootSwitch.toggle()
        }
        root.addView(automationCard)
        root.addView(spacer(12))

        button = Button(this).apply {
            text = "Start root setup"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = regular
            isAllCaps = false
            gravity = Gravity.CENTER
            stateListAnimator = null
            background = rounded(primary, 18)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setOnClickListener {
                when {
                    openManagerOnClick -> openKernelSuManager()
                    enableAutoRootOnClick -> enableAutoRootAfterReboot()
                    else -> confirmOrStart()
                }
            }
        }
        statusActionHost.addView(button)

        val logCard = cardLayout()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(textView("Exploit trace", 19f, textPrimary, regular).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        logState = pill("Idle", textSecondary, surfaceMuted)
        logHeader.addView(logState)
        logCard.addView(logHeader)
        logCard.addView(textView("Kernel, memory and payload trace", 13f, textSecondary).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        log = TextView(this).apply {
            setTextColor(Color.parseColor("#D8DEE9"))
            textSize = 10f
            typeface = monospace
            includeFontPadding = false
            background = rounded(
                palette.logSurface,
                16,
                if (palette.logStroke == Color.TRANSPARENT) null else palette.logStroke
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            isHorizontalScrollBarEnabled = true
            isVerticalScrollBarEnabled = true
            setLineSpacing(dp(2).toFloat(), 1f)
            gravity = Gravity.TOP or Gravity.START
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        logTouchY = event.y
                        logAutoFollow = false
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.y - logTouchY
                        logTouchY = event.y
                        val logCanScroll = when {
                            deltaY > 0f -> view.canScrollVertically(-1)
                            deltaY < 0f -> view.canScrollVertically(1)
                            else -> true
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(logCanScroll)
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            view.performClick()
                        }
                        view.post {
                            logAutoFollow = !view.canScrollVertically(1)
                        }
                    }
                }
                false
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            )
        }
        logCard.addView(log)
        root.addView(logCard)
        root.addView(spacer(12))

        val howItWorksCard = cardLayout().apply {
            isClickable = true
            isFocusable = true
            contentDescription =
                "How SCRoot works. Open the security research blog."
            setOnClickListener {
                openResearchArticle()
            }
            addView(sectionTitle(
                "How it works",
                "Mali GPU exploitation and the KernelSU Next port for SCR-01."
            ))
            addView(textView(
                "Read on hackintoanetwork.com",
                13f,
                primary,
                medium
            ).apply {
                setPadding(0, dp(12), 0, 0)
            })
        }
        root.addView(howItWorksCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            isFillViewport = true
            addView(root)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            addView(scroll)
        }
        setContentView(screen)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val requestedBarAppearance = if (palette.dark) 0 else lightBars
        try {
            window.insetsController?.setSystemBarsAppearance(requestedBarAppearance, lightBars)
        } catch (_: RuntimeException) {

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (palette.dark) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        logln("[SESSION] SCRoot $appVersion")
        logln("[DEVICE] ${Build.MANUFACTURER} ${Build.MODEL} device=${Build.DEVICE}")
        logln("[BUILD] ${Build.DISPLAY}")
        val interruptedAutoStatus = AutoRootPreferences.interruptionStatus(
            exploitRecorded = AutoRootPreferences.currentExploitAttempt(this) != null,
            moduleLoaded = RootFlow.isModuleLoaded()
        )
        val interruptedAutoRoot =
            AutoRootPreferences.automaticAttemptIsOrphaned(
                AutoRootPreferences.currentAttempt(this)?.status,
                pipelineActive = AutoRootService.isActiveInProcess()
            ) &&
                AutoRootPreferences.markInterruptedIfRunning(
                    this,
                    "Automatic setup process stopped before completion",
                    interruptedAutoStatus
                )
        if (interruptedAutoRoot) {
            logln("[FAILED] Automatic setup stopped before completion.")
            if (interruptedAutoStatus == AutoRootPreferences.STATUS_REBOOT_REQUIRED) {
                logln("[CAUTION] Retry is blocked for this boot for safety.")
            } else {
                logln("[INFO] The exploit was not consumed; safety checks may be run again.")
            }
        }
        val mismatch = RootFlow.targetMismatch()
        if (mismatch == null) {
            logln("[TARGET] Exact firmware profile matched")
            setOverallState("Checking", primary, primaryContainer)
            setSetupSummary("Checking root, KernelSU and system UI health.")
            setButtonEnabled(false, "Checking status", primary)
            refreshExistingStateAsync()
        } else {
            logln("[ERROR] Unsupported device or firmware: $mismatch")
            setAutoRootDangerAppearance(true)
            setOverallState("Unsupported", danger, dangerContainer)
            setSetupSummary(
                "This device or firmware is not supported.",
                danger
            )
            setButtonEnabled(
                false,
                "Unavailable on this device",
                danger
            )
        }
        refreshAutoRootSummary()
        if (AutoRootPreferences.currentAttempt(this) != null) {
            loadAutoRootLog()
        }

        scroll.post {
            scroll.scrollTo(0, 0)
        }
    }

    private fun existingKsuState(): ExistingKsuState {
        val moduleLive = RootFlow.isModuleLoaded()
        if (!moduleLive) return ExistingKsuState(false, false, false, false)

        val userspaceReady = try {
            File("/sys/module/ksu_glue/parameters/userspace_ready")
                .readText().trim() == "Y"
        } catch (_: Exception) {
            false
        }
        val moduleManagerAppId = try {
            File("/sys/module/ksu_glue/parameters/manager_appid")
                .readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
        val installedManagerAppId = try {
            packageManager.getApplicationInfo(RootFlow.MANAGER_PKG, 0).uid % 100_000
        } catch (_: Exception) {
            null
        }
        return ExistingKsuState(
            moduleLive = true,
            userspaceReady = userspaceReady,
            managerCrowned =
                moduleManagerAppId != null &&
                    moduleManagerAppId == installedManagerAppId &&
                    RootFlow.isInstalledManagerTrusted(this),
            systemUiIntegrated = RootFlow.isSystemUiIntegratedForCurrentBoot(this)
        )
    }

    private fun refreshExistingStateAsync() {
        if (!activityAlive || RootFlow.targetMismatch() != null) return
        synchronized(stateRefreshLock) {
            stateRefreshGeneration.incrementAndGet()
            stateRefreshRequested = true
            if (stateRefreshWorkerScheduled) return
            stateRefreshWorkerScheduled = true
        }
        try {
            stateExecutor.execute {
                while (activityAlive) {
                    val generation = synchronized(stateRefreshLock) {
                        if (!stateRefreshRequested) {
                            stateRefreshWorkerScheduled = false
                            null
                        } else {
                            stateRefreshRequested = false
                            stateRefreshGeneration.get()
                        }
                    } ?: return@execute
                    val state = try {
                        existingKsuState()
                    } catch (_: RuntimeException) {
                        null
                    }
                    mainHandler.post {
                        if (!activityAlive || generation != stateRefreshGeneration.get()) {
                            return@post
                        }
                        if (pipelineIsActive(
                                rootFlowRunning = RootFlow.isRunning(),
                                autoServiceActive = AutoRootService.isActiveInProcess(),
                                manualGuardActive = ManualFlowGuardService.isActiveInProcess()
                            )
                        ) return@post
                        if (state == null) {
                            setOverallState("Needs attention", danger, dangerContainer)
                            setSetupSummary("The current system state could not be checked.", danger)
                            setButtonEnabled(true, "Check again", primary)
                        } else {
                            renderExistingState(state)
                        }
                    }
                }
                synchronized(stateRefreshLock) {
                    stateRefreshWorkerScheduled = false
                    stateRefreshRequested = false
                }
            }
        } catch (_: RuntimeException) {
            synchronized(stateRefreshLock) {
                stateRefreshWorkerScheduled = false
                stateRefreshRequested = false
            }
            if (activityAlive) {
                setAutoRootDangerAppearance(true)
                setOverallState("Needs attention", danger, dangerContainer)
                setSetupSummary("The current system state could not be checked.", danger)
                setButtonEnabled(true, "Check again", primary)
            }
        }
    }

    private fun renderExistingState(state: ExistingKsuState) {
        openManagerOnClick = false
        enableAutoRootOnClick = false
        setAutoRootDangerAppearance(false)
        val automaticAttempt = AutoRootPreferences.currentAttempt(this)
        if (automaticAttempt?.status == AutoRootPreferences.STATUS_RUNNING) {
            setOverallState("Automatic setup", primary, primaryContainer)
            setSetupSummary("Setting up root and KernelSU automatically. Keep the device powered on.")
            setButtonEnabled(false, "Automatic setup running", primary)
            logState.text = "Automatic"
            logState.setTextColor(primary)
            logState.background = rounded(primaryContainer, 20)
            logStateOnce(
                "automatic-running",
                "[INFO] Automatic boot setup is running."
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        if (RootFlow.isRunning()) {
            running = true
            setOverallState("Running", primary, primaryContainer)
            setSetupSummary("A root setup pipeline is already running. Keep the device powered on.")
            setButtonEnabled(false, "Setup in progress", primary)
            logState.text = "Running"
            logState.setTextColor(primary)
            logState.background = rounded(primaryContainer, 20)
            logStateOnce(
                "pipeline-running",
                "[INFO] The existing setup pipeline is still running."
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopMinimumUptimeCountdown()
        val exploitAttempt = AutoRootPreferences.currentExploitAttempt(this)
        if (!state.moduleLive && exploitAttempt != null) {
            when (exploitAttempt.status) {
                AutoRootPreferences.STATUS_RUNNING,
                AutoRootPreferences.STATUS_REBOOT_REQUIRED -> {
                    logStateOnce(
                        "exploit-reboot-required",
                        "[FAILED] This boot's Mali attempt cannot be repeated safely."
                    )
                    finishReboot()
                    return
                }
                AutoRootPreferences.STATUS_SUCCESS -> {
                    logStateOnce(
                        "exploit-reboot-required-after-root",
                        "[FAILED] Root was acquired without a verified KernelSU module."
                    )
                    logStateOnce(
                        "exploit-hook-reboot-required",
                        "[CAUTION] Reboot is required to clear any temporary kernel hook."
                    )
                    finishReboot()
                    return
                }
            }
        }
        if (automaticAttempt?.status == AutoRootPreferences.STATUS_REBOOT_REQUIRED &&
            !state.moduleLive
        ) {
            logStateOnce(
                "automatic-reboot-required",
                "[FAILED] Reboot required after this boot's automatic attempt."
            )
            finishReboot()
            return
        }

        when {
            state.moduleLive && state.userspaceReady && state.managerCrowned &&
                state.systemUiIntegrated -> {
                openManagerOnClick = true
                logState.text = "Active"
                logState.setTextColor(success)
                logState.background = rounded(successContainer, 20)
                setOverallState("Rooted", success, successContainer)
                setSetupSummary(
                    "Temporary root, KernelSU Next and the SCR-01 system UI are ready."
                )
                setButtonEnabled(true, "Open KernelSU", success)
                logStateOnce(
                    "rooted",
                    "[OK] The existing KernelSU environment is active."
                )
            }
            state.moduleLive && state.userspaceReady && state.managerCrowned -> {
                openManagerOnClick = false
                logState.text = "UI repair"
                logState.setTextColor(warning)
                logState.background = rounded(warningContainer, 20)
                setOverallState("Repair available", warning, warningContainer)
                setSetupSummary(
                    "Root and KernelSU are ready. Finish the Apps screen and Root menu setup."
                )
                setButtonEnabled(true, "Repair launcher integration", primary)
                logStateOnce(
                    "system-ui-repair",
                    "[INFO] KernelSU is active; launcher integration is not verified for this boot.",
                    "Repair does not repeat the kernel exploit."
                )
            }
            state.moduleLive -> {
                openManagerOnClick = false
                logState.text = "Repair"
                logState.setTextColor(warning)
                logState.background = rounded(warningContainer, 20)
                setOverallState("Repair available", warning, warningContainer)
                setSetupSummary("Root is active, but SCRoot needs to finish the setup.")
                setButtonEnabled(true, "Repair setup", primary)
                logStateOnce(
                    "module-repair",
                    "[INFO] The kernel module is already active.",
                    "Repair checks userspace setup without repeating the exploit."
                )
            }
            else -> {
                if (RootFlow.currentExploitWindowExpired()) {
                    showFreshBootWindowExpired()
                } else {
                    openManagerOnClick = false
                    logState.text = "Idle"
                    logState.setTextColor(textSecondary)
                    logState.background = rounded(surfaceMuted, 20)
                    setOverallState("Not installed", primary, primaryContainer)
                    setSetupSummary("Tap the button below to install root and KernelSU.")
                    setButtonEnabled(true, "Start root setup", primary)
                    logStateOnce(
                        "not-installed",
                        "[INFO] Manual setup can be started."
                    )
                }
            }
        }
    }

    private fun showFreshBootWindowExpired() {
        openManagerOnClick = false
        enableAutoRootOnClick = !autoRootSwitch.isChecked
        setAutoRootDangerAppearance(true)
        logState.text = "Reboot"
        logState.setTextColor(danger)
        logState.background = rounded(dangerContainer, 20)
        setOverallState("Reboot required", danger, dangerContainer)
        if (autoRootSwitch.isChecked) {
            setSetupSummary(AUTO_ROOT_RESTART_MESSAGE, danger)
            setButtonEnabled(false, "REBOOT THE DEVICE", danger)
        } else {
            setSetupSummary("240 seconds passed. Reboot and try again.", danger)
            setButtonEnabled(true, "Enable auto root", danger)
        }
        logStateOnce(
            if (autoRootSwitch.isChecked) {
                "fresh-boot-window-expired-auto-enabled"
            } else {
                "fresh-boot-window-expired"
            },
            "[BLOCKED] The 240-second fresh-boot window has elapsed.",
            "Reboot the device, then open SCRoot and try again."
        )
    }

    private fun enableAutoRootAfterReboot() {
        if (!AutoRootPreferences.setEnabled(this, true)) {
            Toast.makeText(
                this,
                "Auto root could not be enabled.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        changingAutoRootSwitch = true
        autoRootSwitch.isChecked = true
        changingAutoRootSwitch = false
        refreshAutoRootSummary()
        showFreshBootWindowExpired()
        AlertDialog.Builder(this)
            .setTitle("Auto root enabled")
            .setMessage(AUTO_ROOT_RESTART_MESSAGE)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun logStateOnce(key: String, vararg messages: String) {
        if (lastStateLogKey == key) return
        lastStateLogKey = key
        messages.forEach(::logln)
    }

    private fun refreshAutoRootSummary() {
        val enabled = autoRootSwitch.isChecked
        val attempt = AutoRootPreferences.currentAttempt(this)
        autoRootSummary.text = when (attempt?.status) {
            AutoRootPreferences.STATUS_RUNNING ->
                "This boot : automatic setup running"
            AutoRootPreferences.STATUS_SUCCESS ->
                "This boot : automatic setup complete"
            AutoRootPreferences.STATUS_REBOOT_REQUIRED ->
                "This boot : failed, reboot required"
            AutoRootPreferences.STATUS_SAFE_FAILURE ->
                "This boot : setup needs attention"
            else -> if (enabled) {
                "On · Auto root after reboot"
            } else {
                "Off · Manual setup only"
            }
        }
        val statusColor = when (attempt?.status) {
                AutoRootPreferences.STATUS_SUCCESS -> success
                AutoRootPreferences.STATUS_REBOOT_REQUIRED -> danger
                AutoRootPreferences.STATUS_SAFE_FAILURE -> warning
                AutoRootPreferences.STATUS_RUNNING -> primary
                else -> textSecondary
            }
        autoRootSummary.setTextColor(if (autoRootDangerState) danger else statusColor)
    }

    private fun setAutoRootDangerAppearance(enabled: Boolean) {
        autoRootDangerState = enabled
        if (!::autoRootSwitch.isInitialized) return
        autoRootSwitch.thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(if (enabled) danger else success, palette.switchThumbOff)
        )
        autoRootSwitch.trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                if (enabled) palette.dangerAction else palette.switchTrackOn,
                palette.switchTrackOff
            )
        )
        if (::autoRootSummary.isInitialized) refreshAutoRootSummary()
    }

    private fun loadAutoRootLog() {
        val lines = try {
            File(
                AutoRootPreferences.deviceProtectedContext(this).filesDir,
                "auto-root.log"
            ).readLines().takeLast(400)
        } catch (_: Exception) {
            emptyList()
        }
        if (lines.isEmpty()) return
        logln("── boot trace ──")
        val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")
        lines.forEach { line ->
            logln(line.replaceFirst(timestamp, ""))
        }
    }

    private fun setOverallState(
        state: String,
        foreground: Int,
        container: Int
    ) = runOnUiThread {
        if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
        overallState.text = state
        overallState.setTextColor(foreground)
        overallState.background = rounded(container, 20)
    }

    private fun setSetupSummary(
        message: String,
        color: Int = textSecondary
    ) = runOnUiThread {
        if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
        setupSummary.text = message
        setupSummary.setTextColor(color)
    }

    private fun setButtonEnabled(enabled: Boolean, label: String, color: Int) = runOnUiThread {
        if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
        val navigationAction = enabled && label == "Open KernelSU"
        val rebootInstruction = !enabled && label == "REBOOT THE DEVICE"
        button.isEnabled = enabled
        button.text = if (navigationAction) "$label  ›" else label
        button.alpha = if (enabled || rebootInstruction) 1f else 0.58f
        button.textSize = if (rebootInstruction) 18f else 15f
        button.typeface = if (navigationAction || rebootInstruction) medium else regular
        button.gravity = if (navigationAction) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }
        button.setTextColor(if (navigationAction) primary else Color.WHITE)
        button.setPadding(
            if (navigationAction) 0 else dp(18),
            0,
            if (navigationAction) 0 else dp(18),
            0
        )
        (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = dp(if (navigationAction) 48 else 52)
            button.layoutParams = params
        }
        val actionColor = if (palette.dark) {
            when (color) {
                primary -> palette.primaryAction
                success -> palette.successAction
                danger -> palette.dangerAction
                else -> color
            }
        } else {
            color
        }
        button.background = if (navigationAction) {
            rounded(Color.TRANSPARENT, 18)
        } else {
            rounded(actionColor, 18)
        }
    }

    private fun shouldHideLogLine(message: String): Boolean {
        val trimmed = message.trimStart()
        return trimmed.startsWith("[bringup ") ||
            trimmed.startsWith("[SESSION]") ||
            trimmed.startsWith("[DEVICE]") ||
            trimmed.startsWith("[BUILD]") ||
            trimmed.startsWith("[target]")
    }

    private fun logColor(message: String): Int =
        when {
            message.contains("[ERROR]") ||
                message.contains("[FAILED]") -> logErrorColor
            message == ALLOCATOR_CAUTION_DISPLAY ||
                message.startsWith(BootTracePresentation.MINIMUM_UPTIME_PREFIX) ||
                message.contains("[CAUTION]") -> logErrorColor
            message.contains("[WARNING]") ||
                message.contains("[BLOCKED]") -> logWarningColor
            message.contains("[OK]") ||
                message.contains("[READY]") -> logOkColor
            message == BootTracePresentation.MINIMUM_UPTIME_COMPLETE -> logOkColor
            message.startsWith("[1/3]") ||
                message.startsWith("[2/3]") ||
                message.startsWith("[3/3]") -> logStageColor
            message.contains("[payload]") ||
                message.contains("[stage]") ||
                message.contains("[broadcast]") -> logPayloadColor
            message.contains("[time]") ||
                message.contains("[vmstat]") ||
                message.contains("[psi]") ||
                message.contains("[memory]") ||
                message.startsWith(BootTracePresentation.MINIMUM_UPTIME_PREFIX) ->
                logTelemetryColor
            else -> logDefaultColor
        }

    private fun logln(message: String) {
        val boundedMessage = if (message.length <= MAX_LOG_LINE_CHARS) {
            message
        } else {
            message.take(MAX_LOG_LINE_CHARS - 1) + "…"
        }
        if (shouldHideLogLine(boundedMessage) || !activityAlive) return
        val waitDurationMs = BootTracePresentation.minimumUptimeWaitMs(boundedMessage)
        val presented = when {
            waitDurationMs != null -> {
                minimumUptimeDeadlineElapsedMs =
                    SystemClock.elapsedRealtime() + waitDurationMs
                mainHandler.removeCallbacks(minimumUptimeCountdownRunnable)
                mainHandler.post(minimumUptimeCountdownRunnable)
                BootTracePresentation.minimumUptimeCountdownText(waitDurationMs)
            }
            boundedMessage.trim() == ALLOCATOR_READY_RAW -> {
                stopMinimumUptimeCountdown()
                ALLOCATOR_READY_DISPLAY
            }
            boundedMessage.trim() == ALLOCATOR_CAUTION_RAW -> ALLOCATOR_CAUTION_DISPLAY
            else -> boundedMessage
        }
        synchronized(pendingLogLock) {
            while (pendingLogLines.isNotEmpty() &&
                (pendingLogLines.size >= MAX_PENDING_LOG_LINES ||
                    pendingLogChars + presented.length > MAX_PENDING_LOG_CHARS)
            ) {
                pendingLogChars -= pendingLogLines.removeFirst().length
            }
            pendingLogLines.addLast(presented)
            pendingLogChars += presented.length
        }
        if (logFlushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(flushLogRunnable, LOG_FLUSH_DELAY_MS)
        }
    }

    private fun flushPendingLogs() {
        if (!activityAlive || !::log.isInitialized) {
            synchronized(pendingLogLock) {
                pendingLogLines.clear()
                pendingLogChars = 0
            }
            logFlushScheduled.set(false)
            return
        }
        val batch = ArrayList<String>(LOG_FLUSH_BATCH)
        synchronized(pendingLogLock) {
            repeat(minOf(LOG_FLUSH_BATCH, pendingLogLines.size)) {
                val line = pendingLogLines.removeFirst()
                pendingLogChars -= line.length
                batch.add(line)
            }
        }
        if (batch.contains(ALLOCATOR_READY_DISPLAY)) {

            batch.removeAll { it == ALLOCATOR_CAUTION_DISPLAY }
            batch.indices.forEach { index ->
                if (batch[index].startsWith(BootTracePresentation.MINIMUM_UPTIME_PREFIX)) {
                    batch[index] = BootTracePresentation.MINIMUM_UPTIME_COMPLETE
                }
            }
            val visible = log.text
            if (visible is SpannableStringBuilder) {
                val marker = "$ALLOCATOR_CAUTION_DISPLAY\n"
                val start = TextUtils.indexOf(visible, marker)
                if (start >= 0) visible.delete(start, start + marker.length)
            }
            replaceMinimumUptimeLine(
                BootTracePresentation.MINIMUM_UPTIME_COMPLETE,
                logOkColor
            )
        }
        val rendered = SpannableStringBuilder()
        batch.forEach { message ->
            val start = rendered.length
            rendered.append(if (message.isBlank()) "\n" else "$message\n")
            if (message.isNotBlank()) {
                rendered.setSpan(
                    ForegroundColorSpan(logColor(message)),
                    start,
                    rendered.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (rendered.isNotEmpty()) log.append(rendered)
        refreshMinimumUptimeCountdown()
        val visible = log.text
        if (visible is SpannableStringBuilder && visible.length > MAX_VISIBLE_LOG_CHARS) {
            val target = visible.length - MAX_VISIBLE_LOG_CHARS
            val boundary = TextUtils.indexOf(visible, '\n', target)
            visible.delete(0, if (boundary >= 0) boundary + 1 else target)
        }
        if (logAutoFollow) {
            val scrollAmount =
                log.layout?.let { it.getLineTop(log.lineCount) - log.height } ?: 0
            if (scrollAmount > 0) log.scrollTo(0, scrollAmount) else log.scrollTo(0, 0)
        }
        logFlushScheduled.set(false)
        val hasMore = synchronized(pendingLogLock) { pendingLogLines.isNotEmpty() }
        if (hasMore && logFlushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(flushLogRunnable, LOG_FLUSH_DELAY_MS)
        }
    }

    private fun refreshMinimumUptimeCountdown() {
        val deadline = minimumUptimeDeadlineElapsedMs ?: return
        if (!::log.isInitialized) return
        val remainingMs = deadline - SystemClock.elapsedRealtime()
        val replacement = BootTracePresentation.minimumUptimeCountdownText(remainingMs)
        replaceMinimumUptimeLine(replacement, logErrorColor)
    }

    private fun replaceMinimumUptimeLine(replacement: String, color: Int) {
        if (!::log.isInitialized) return
        val visible = log.text as? SpannableStringBuilder ?: return
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

    private fun updateProgressFromLog(line: String) {
        val summary = when {
            line.startsWith("[1/3]") ->
                "Preparing root access. Keep this app open."
            line.startsWith("[2/3]") ->
                "Installing KernelSU. Keep this app open."
            line.startsWith("[2.5/3]") ->
                "Integrating the Apps screen, Root menu and native Recents."
            line.contains("manager verify:") ->
                "Connecting KernelSU Manager."
            else -> null
        } ?: return
        runOnUiThread {
            if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
            setSetupSummary(summary)
        }
    }

    private fun confirmOrStart() {
        if (running || RootFlow.isRunning()) return
        if (RootFlow.isModuleLoaded()) {
            start()
            return
        }
        if (RootFlow.currentExploitWindowExpired()) {
            showFreshBootWindowExpired()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage("Do not retry in the same boot after a failure. If setup fails, reboot the device before trying again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> start() }
            .show()
    }

    private fun openKernelSuManager() {
        val intent = packageManager.getLaunchIntentForPackage(RootFlow.MANAGER_PKG)
        if (intent == null) {
            openManagerOnClick = false
            logln("[ERROR] KernelSU Manager could not be opened.")
            setButtonEnabled(true, "Repair setup", primary)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            openManagerOnClick = false
            logln("[ERROR] Android rejected the KernelSU Manager launch.")
            setButtonEnabled(true, "Repair setup", primary)
        }
    }

    private fun openResearchArticle() {
        openExternalPage(
            RESEARCH_URL,
            "Unable to open the research blog."
        )
    }

    private fun openExternalPage(url: String, errorMessage: String) {
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(page)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                errorMessage,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun start() {
        if (pipelineIsActive(
                rootFlowRunning = running || RootFlow.isRunning(),
                autoServiceActive = AutoRootService.isActiveInProcess(),
                manualGuardActive = ManualFlowGuardService.isActiveInProcess()
            )) return
        if (!ManualFlowGuardService.reservePipeline()) return
        if (!ManualFlowGuardService.start(this)) {
            ManualFlowGuardService.releasePipelineReservation()
            logln("[ERROR] Android rejected the manual setup process guard.")
            finishUi(false, "Try setup again")
            return
        }
        openManagerOnClick = false
        running = true
        pipelineWasObserved = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logState.text = "Running"
        logState.setTextColor(primary)
        logState.background = rounded(primaryContainer, 20)
        setOverallState("Running", primary, primaryContainer)
        setSetupSummary("Setting up root and KernelSU. Keep this app open.")
        setButtonEnabled(false, "Setup in progress", primary)
        val owner = WeakReference(this)
        val appContext = applicationContext
        val maxExploitTries = manualExploitAttemptLimit(RootFlow.isModuleLoaded())
        try {
            thread(name = "SCRoot-Manual") {
                try {
                    if (ManualFlowGuardService.awaitActive()) {
                        runManualFlow(appContext, owner, maxExploitTries)
                    } else {
                        owner.get()?.let { activity ->
                            activity.logln(
                                "[ERROR] The manual setup process guard did not become active."
                            )
                            activity.finishUi(false, "Try setup again")
                        }
                    }
                } finally {
                    ManualFlowGuardService.stop(appContext)
                    ManualFlowGuardService.releasePipelineReservation()
                }
            }
        } catch (error: RuntimeException) {
            ManualFlowGuardService.stop(appContext)
            ManualFlowGuardService.releasePipelineReservation()
            logln("[ERROR] The manual setup worker could not start.")
            finishUi(false, "Try setup again")
        }
    }

    private fun finishUi(successful: Boolean, retryLabel: String? = null) = runOnUiThread {
        if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
        running = false
        pipelineWasObserved = false
        stopMinimumUptimeCountdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (successful) {
            setAutoRootDangerAppearance(false)
            openManagerOnClick = true
            logState.text = "Done"
            logState.setTextColor(success)
            logState.background = rounded(successContainer, 20)
            setOverallState("Rooted", success, successContainer)
            setSetupSummary(
                "Temporary root, KernelSU Next and the SCR-01 system UI are ready."
            )
            setButtonEnabled(true, "Open KernelSU", success)
        } else {
            setAutoRootDangerAppearance(true)
            logState.text = "Review"
            logState.setTextColor(danger)
            logState.background = rounded(dangerContainer, 20)
            setOverallState("Needs attention", danger, dangerContainer)
            setSetupSummary(
                "Setup did not finish. Review the trace below.",
                danger
            )
            setButtonEnabled(
                true,
                retryLabel ?: "Check again",
                primary
            )
        }
    }

    private fun finishReboot() = runOnUiThread {
        if (!activityAlive || isFinishing || isDestroyed) return@runOnUiThread
        running = false
        pipelineWasObserved = false
        stopMinimumUptimeCountdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setAutoRootDangerAppearance(true)
        logState.text = "Reboot"
        logState.setTextColor(danger)
        logState.background = rounded(dangerContainer, 20)
        setOverallState("Reboot required", danger, dangerContainer)
        setSetupSummary(
            "Restart the device before trying again.",
            danger
        )
        setButtonEnabled(false, "Reboot before retrying", danger)
    }

    private fun finishAfterUnexpectedFailure() {
        val moduleLive = RootFlow.isModuleLoaded()
        val exploitAttempt = AutoRootPreferences.currentExploitAttempt(this)
        if (!moduleLive && exploitAttempt != null) {
            finishReboot()
        } else {
            finishUi(false, "Retry setup repair")
        }
    }

    override fun onResume() {
        super.onResume()
        val active = pipelineIsActive(
            rootFlowRunning = RootFlow.isRunning(),
            autoServiceActive = AutoRootService.isActiveInProcess(),
            manualGuardActive = ManualFlowGuardService.isActiveInProcess()
        )
        if (active) {
            running = true
            pipelineWasObserved = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mainHandler.removeCallbacks(pipelinePollRunnable)
            mainHandler.post(pipelinePollRunnable)
        } else if (::overallState.isInitialized && RootFlow.targetMismatch() == null) {
            refreshExistingStateAsync()
            refreshAutoRootSummary()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(pipelinePollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        activityAlive = false
        stateRefreshGeneration.incrementAndGet()
        synchronized(stateRefreshLock) {
            stateRefreshRequested = false
        }
        stateExecutor.shutdownNow()
        stopMinimumUptimeCountdown()
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(pendingLogLock) {
            pendingLogLines.clear()
            pendingLogChars = 0
        }
        logFlushScheduled.set(false)
        super.onDestroy()
    }
}
