package com.scr01.scroot

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSafetyPolicyTest {

    @Test
    fun manualRepairDisablesNativeExploitAttempts() {
        assertEquals(0, manualExploitAttemptLimit(moduleLive = true))
        assertEquals(1, manualExploitAttemptLimit(moduleLive = false))
    }

    @Test
    fun mainUiKeepsPollingDuringTheServicePreparationWindow() {
        assertTrue(
            pipelineIsActive(
                rootFlowRunning = false,
                autoServiceActive = true
            )
        )
        assertTrue(
            pipelineIsActive(
                rootFlowRunning = true,
                autoServiceActive = false
            )
        )
        assertTrue(
            pipelineIsActive(
                rootFlowRunning = false,
                autoServiceActive = false,
                manualGuardActive = true
            )
        )
        assertFalse(
            pipelineIsActive(
                rootFlowRunning = false,
                autoServiceActive = false
            )
        )
    }

    @Test
    fun rejectedServiceStartCannotStopAnActivePipeline() {
        assertFalse(AutoRootService.shouldStopAfterRejectedStart(currentlyRunning = true))
        assertTrue(AutoRootService.shouldStopAfterRejectedStart(currentlyRunning = false))
        assertFalse(
            ManualFlowGuardService.shouldStopAfterRejectedStart(currentlyActive = true)
        )
        assertTrue(
            ManualFlowGuardService.shouldStopAfterRejectedStart(currentlyActive = false)
        )
    }

    @Test
    fun manualPipelineReservationIsExclusiveUntilItsOwnerReleasesIt() {
        ManualFlowGuardService.releasePipelineReservation()
        try {
            assertTrue(ManualFlowGuardService.reservePipeline())
            assertFalse(ManualFlowGuardService.reservePipeline())
            assertTrue(ManualFlowGuardService.isPipelineReservedInProcess())
            assertTrue(ManualFlowGuardService.isActiveInProcess())
        } finally {
            ManualFlowGuardService.releasePipelineReservation()
        }
        assertFalse(ManualFlowGuardService.isPipelineReservedInProcess())
    }

    @Test
    fun automaticServiceLaunchReservationClosesTheOnCreateGap() {
        AutoRootService.releaseLaunchReservation()
        try {
            assertTrue(AutoRootService.reserveLaunch())
            assertFalse(AutoRootService.reserveLaunch())
            assertTrue(AutoRootService.isLaunchReservedInProcess())
            assertTrue(AutoRootService.isActiveInProcess())
        } finally {
            AutoRootService.releaseLaunchReservation()
        }
        assertFalse(AutoRootService.isLaunchReservedInProcess())
    }

    @Test
    fun automaticLogMessagesAreSingleLineBoundedAndControlFree() {
        val value = "line one\nline two\u0000end" + "x".repeat(5_000)
        val bounded = AutoRootService.boundedAutoLogMessage(value)
        assertEquals(4_096, bounded.length)
        assertTrue(bounded.endsWith("…"))
        assertFalse(bounded.any { it.code in 0x00..0x1f || it.code == 0x7f })
    }

    @Test
    fun automaticLogRotationIncludesThePendingEntry() {
        assertFalse(AutoRootService.autoLogNeedsRotation(512L * 1024L - 32L, 32))
        assertTrue(AutoRootService.autoLogNeedsRotation(512L * 1024L - 31L, 32))
        assertTrue(AutoRootService.autoLogNeedsRotation(-1L, 1))
        assertTrue(AutoRootService.autoLogNeedsRotation(0L, -1))
    }

    @Test
    fun pipelineWakeLockCoversTheBoundedRecoveryPath() {
        assertTrue(ROOT_PIPELINE_WAKELOCK_TIMEOUT_MS >= 20L * 60L * 1_000L)
        assertTrue(ROOT_PIPELINE_WAKELOCK_TIMEOUT_MS <= 30L * 60L * 1_000L)
    }

    @Test
    fun procStatStartTimeParsingSurvivesSpacesAndParenthesesInProcessNames() {
        val prefix = "123 (name with ) parenthesis) "
        val fields = mutableListOf("S")
        fields.addAll((4..21).map { it.toString() })
        fields.add("987654")
        fields.addAll(listOf("23", "24"))
        assertEquals(
            987654L,
            RootFlow.processStartTimeFromStat(prefix + fields.joinToString(" "))
        )
        assertEquals(null, RootFlow.processStartTimeFromStat("malformed"))
        assertEquals(
            null,
            RootFlow.processStartTimeFromStat("1 (name) S 1 2 3")
        )
    }

    @Test
    fun traceBusAcceptsEventsOnlyDuringAnActiveRun() {
        assertFalse(BootTraceBus.acceptsPublishedEvents(BootTraceBus.RunState.IDLE))
        assertTrue(BootTraceBus.acceptsPublishedEvents(BootTraceBus.RunState.RUNNING))
        assertFalse(BootTraceBus.acceptsPublishedEvents(BootTraceBus.RunState.SUCCESS))
        assertFalse(BootTraceBus.acceptsPublishedEvents(BootTraceBus.RunState.FAILURE))
    }

    @Test
    fun systemUiReceiptRequiresLiveQuickstepAndBrokerHealth() {
        assertTrue(
            RootFlow.overviewHealthSignalsReady(
                protocol = 1,
                buildId = "scroverview-0.4.7",
                quickstepBound = true,
                brokerReady = true
            )
        )
        assertFalse(
            RootFlow.overviewHealthSignalsReady(
                protocol = 2,
                buildId = "scroverview-0.4.7",
                quickstepBound = true,
                brokerReady = true
            )
        )
        assertFalse(
            RootFlow.overviewHealthSignalsReady(
                protocol = 1,
                buildId = "scroverview-0.4.7",
                quickstepBound = false,
                brokerReady = true
            )
        )
        assertFalse(
            RootFlow.overviewHealthSignalsReady(
                protocol = 1,
                buildId = "scroverview-0.4.7",
                quickstepBound = true,
                brokerReady = false
            )
        )
        assertFalse(
            RootFlow.overviewHealthSignalsReady(
                protocol = 1,
                buildId = "scroverview-0.4.6",
                quickstepBound = true,
                brokerReady = true
            )
        )
    }

    @Test
    fun systemUiReceiptRequiresAnAuthenticatedLiveHomeResponse() {
        val nonce = "01234567-89ab-cdef-0123-456789abcdef"
        assertTrue(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0x5343,
                protocol = 1,
                buildId = "scr01-home-1.7.27",
                expectedNonce = nonce,
                returnedNonce = nonce,
                homeReady = true
            )
        )
        assertFalse(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0x5343,
                protocol = 1,
                buildId = "scr01-home-1.7.27",
                expectedNonce = nonce,
                returnedNonce = "fedcba98-7654-3210-fedc-ba9876543210",
                homeReady = true
            )
        )
        assertFalse(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0,
                protocol = 1,
                buildId = "scr01-home-1.7.27",
                expectedNonce = nonce,
                returnedNonce = nonce,
                homeReady = true
            )
        )
        assertFalse(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0x5343,
                protocol = 2,
                buildId = "scr01-home-1.7.27",
                expectedNonce = nonce,
                returnedNonce = nonce,
                homeReady = true
            )
        )
        assertFalse(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0x5343,
                protocol = 1,
                buildId = "scr01-home-1.7.27",
                expectedNonce = nonce,
                returnedNonce = nonce,
                homeReady = false
            )
        )
        assertFalse(
            RootFlow.homeHealthSignalsReady(
                resultCode = 0x5343,
                protocol = 1,
                buildId = "scr01-home-1.7.26",
                expectedNonce = nonce,
                returnedNonce = nonce,
                homeReady = true
            )
        )
    }

    @Test
    fun sysfsModuleEvidenceWinsWhenProcRowIsHidden() {
        assertTrue(
            RootFlow.moduleStateFromSignals(
                procText = "other_module 4096 0 - Live 0x0\n",
                sysfsVisible = true
            ) == RootFlow.ModuleState.PRESENT
        )
    }

    @Test
    fun unreadableModuleSignalsFailClosed() {
        assertTrue(
            RootFlow.moduleStateFromSignals(
                procText = null,
                sysfsVisible = false
            ) == RootFlow.ModuleState.UNKNOWN
        )
    }

    @Test
    fun unreadableModuleStateStartsOnlyBeforeAnyBootAttempt() {
        assertTrue(
            RootFlow.unknownModuleStateMayStartFresh(
                RootFlow.ModuleState.UNKNOWN,
                exploitRecorded = false
            )
        )
        assertFalse(
            RootFlow.unknownModuleStateMayStartFresh(
                RootFlow.ModuleState.UNKNOWN,
                exploitRecorded = true
            )
        )
        assertFalse(
            RootFlow.unknownModuleStateMayStartFresh(
                RootFlow.ModuleState.ABSENT,
                exploitRecorded = false
            )
        )
        assertFalse(
            RootFlow.unknownModuleStateMayStartFresh(
                RootFlow.ModuleState.PRESENT,
                exploitRecorded = false
            )
        )
    }

    @Test
    fun manualExecutionRequiresTopApp() {
        assertTrue(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.MANUAL,
                "top-app"
            )
        )
        assertFalse(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.MANUAL,
                "foreground"
            )
        )
        assertFalse(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.MANUAL,
                "background"
            )
        )
        assertFalse(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.MANUAL,
                "unknown"
            )
        )
    }

    @Test
    fun automaticExecutionAllowsOnlyForegroundClasses() {
        assertTrue(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.AUTO,
                "top-app"
            )
        )
        assertTrue(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.AUTO,
                "foreground"
            )
        )
        assertFalse(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.AUTO,
                "background"
            )
        )
        assertFalse(
            RootFlow.executionClassAllowed(
                RootFlow.ExecutionMode.AUTO,
                "unknown"
            )
        )
    }

    @Test
    fun memoryFallbackRequiresFloorHeadroomAndStableTrend() {
        assertTrue(
            RootFlow.fallbackMemorySampleIsQuiet(
                availableKb = 900_000,
                previousAvailableKb = 930_000,
                lowMemory = false,
                thresholdKb = 250_000
            )
        )
        assertFalse(
            RootFlow.fallbackMemorySampleIsQuiet(
                availableKb = 749_999,
                previousAvailableKb = null,
                lowMemory = false,
                thresholdKb = 250_000
            )
        )
        assertFalse(
            RootFlow.fallbackMemorySampleIsQuiet(
                availableKb = 900_000,
                previousAvailableKb = 1_050_000,
                lowMemory = false,
                thresholdKb = 250_000
            )
        )
        assertFalse(
            RootFlow.fallbackMemorySampleIsQuiet(
                availableKb = 900_000,
                previousAvailableKb = 900_000,
                lowMemory = true,
                thresholdKb = 250_000
            )
        )
    }

    @Test
    fun nativeEntryRejectsObservedLowFreeFailureState() {
        assertFalse(
            RootFlow.nativeEntryMemoryIsSafe(
                RootFlow.MemoryState(
                    freeKb = 27_684,
                    availableKb = 1_227_736,
                    cachedKb = 800_000
                )
            )
        )
        assertFalse(
            RootFlow.nativeEntryMemoryIsSafe(
                RootFlow.MemoryState(
                    freeKb = 0,
                    availableKb = 1_200_000,
                    cachedKb = 800_000
                )
            )
        )
    }

    @Test
    fun nativeEntryRequiresBothFreeAndAvailableFloors() {
        assertTrue(
            RootFlow.nativeEntryMemoryIsSafe(
                RootFlow.MemoryState(
                    freeKb = 80_000,
                    availableKb = 750_000,
                    cachedKb = 500_000
                )
            )
        )
        assertFalse(
            RootFlow.nativeEntryMemoryIsSafe(
                RootFlow.MemoryState(
                    freeKb = 250_000,
                    availableKb = 749_999,
                    cachedKb = 300_000
                )
            )
        )
    }

    @Test
    fun moduleParserUsesExactFirstField() {
        assertTrue(
            RootFlow.moduleStateFromProcText(
                "ksu_glue 131072 0 - Live 0x00000000\nother 4096 0 - Live 0x0\n"
            ) == RootFlow.ModuleState.PRESENT
        )
        assertTrue(
            RootFlow.moduleStateFromProcText(
                "ksu_glue_debug 131072 0 - Live 0x00000000\n"
            ) == RootFlow.ModuleState.ABSENT
        )
    }

    @Test
    fun interruptionRequiresRebootOnlyAfterAnUnresolvedExploit() {
        assertTrue(
            AutoRootPreferences.interruptionStatus(
                exploitRecorded = true,
                moduleLoaded = false
            ) == AutoRootPreferences.STATUS_REBOOT_REQUIRED
        )
        assertTrue(
            AutoRootPreferences.interruptionStatus(
                exploitRecorded = false,
                moduleLoaded = false
            ) == AutoRootPreferences.STATUS_SAFE_FAILURE
        )
        assertTrue(
            AutoRootPreferences.interruptionStatus(
                exploitRecorded = true,
                moduleLoaded = true
            ) == AutoRootPreferences.STATUS_SAFE_FAILURE
        )
    }

    @Test
    fun freshBootWindowExpiresOnlyAfterTheExact240SecondBoundary() {
        assertFalse(RootFlow.exploitWindowExpired(239.999))
        assertFalse(RootFlow.exploitWindowExpired(240.0))
        assertTrue(RootFlow.exploitWindowExpired(240.001))
        assertTrue(RootFlow.exploitWindowExpired(Double.NaN))
        assertTrue(RootFlow.exploitWindowExpired(-1.0))
    }

    @Test
    fun staleAutomaticRunningStateIsOnlyOrphanedWithoutALivePipeline() {
        assertTrue(
            AutoRootPreferences.automaticAttemptIsOrphaned(
                AutoRootPreferences.STATUS_RUNNING,
                pipelineActive = false
            )
        )
        assertFalse(
            AutoRootPreferences.automaticAttemptIsOrphaned(
                AutoRootPreferences.STATUS_RUNNING,
                pipelineActive = true
            )
        )
        assertFalse(
            AutoRootPreferences.automaticAttemptIsOrphaned(
                AutoRootPreferences.STATUS_SUCCESS,
                pipelineActive = false
            )
        )
        assertFalse(
            AutoRootPreferences.automaticAttemptIsOrphaned(
                null,
                pipelineActive = false
            )
        )
    }

    @Test
    fun persistedDetailsAndReceiptCountersStayBounded() {
        val oversized = "x".repeat(10_000)
        val bounded = AutoRootPreferences.boundedDetail(oversized)
        assertEquals(512, bounded.length)
        assertTrue(bounded.endsWith("…"))
        assertEquals(
            "line one line two end",
            AutoRootPreferences.boundedDetail("line one\nline two\u0000end")
        )
        assertEquals(1, AutoRootPreferences.incrementReceiptCount(-7))
        assertEquals(1, AutoRootPreferences.incrementReceiptCount(0))
        assertEquals(42, AutoRootPreferences.incrementReceiptCount(41))
        assertEquals(
            Int.MAX_VALUE,
            AutoRootPreferences.incrementReceiptCount(Int.MAX_VALUE)
        )
    }

    @Test
    fun bootIdMustUseTheCanonicalKernelUuidShape() {
        assertTrue(
            AutoRootPreferences.validBootId("01234567-89ab-cdef-0123-456789abcdef")
        )
        assertTrue(
            AutoRootPreferences.validBootId("01234567-89AB-CDEF-0123-456789ABCDEF")
        )
        assertFalse(AutoRootPreferences.validBootId("0123456789abcdef0123456789abcdef"))
        assertFalse(AutoRootPreferences.validBootId("--------"))
        assertFalse(
            AutoRootPreferences.validBootId("01234567-89ab-cdef-0123-456789abcdef-extra")
        )
    }

    @Test
    fun corruptedPersistedStatusesFailClosed() {
        assertEquals(
            AutoRootPreferences.STATUS_SAFE_FAILURE,
            AutoRootPreferences.normalizedAttemptStatus("corrupt")
        )
        assertEquals(
            AutoRootPreferences.STATUS_REBOOT_REQUIRED,
            AutoRootPreferences.normalizedExploitStatus("corrupt")
        )
        assertEquals(
            AutoRootPreferences.STATUS_RUNNING,
            AutoRootPreferences.normalizedAttemptStatus(
                AutoRootPreferences.STATUS_RUNNING
            )
        )
        assertEquals(null, AutoRootPreferences.normalizedAttemptStatus(null))
    }

    @Test
    fun automaticAttemptTerminalStateCannotBeOverwritten() {
        val terminalStates = listOf(
            AutoRootPreferences.STATUS_SUCCESS,
            AutoRootPreferences.STATUS_SAFE_FAILURE,
            AutoRootPreferences.STATUS_REBOOT_REQUIRED
        )
        terminalStates.forEach { requested ->
            assertTrue(
                AutoRootPreferences.automaticFinishAllowed(
                    AutoRootPreferences.STATUS_RUNNING,
                    requested
                )
            )
            terminalStates.forEach { current ->
                assertFalse(
                    AutoRootPreferences.automaticFinishAllowed(current, requested)
                )
            }
        }
        assertFalse(
            AutoRootPreferences.automaticFinishAllowed(
                AutoRootPreferences.STATUS_RUNNING,
                AutoRootPreferences.STATUS_RUNNING
            )
        )
        assertFalse(AutoRootPreferences.automaticFinishAllowed(null, "corrupt"))
    }

    @Test
    fun nativeAttemptTerminalStateCannotBeOverwritten() {
        listOf(
            AutoRootPreferences.STATUS_SUCCESS,
            AutoRootPreferences.STATUS_REBOOT_REQUIRED
        ).forEach { requested ->
            assertTrue(
                AutoRootPreferences.exploitFinishAllowed(
                    AutoRootPreferences.STATUS_RUNNING,
                    requested
                )
            )
            assertFalse(
                AutoRootPreferences.exploitFinishAllowed(
                    AutoRootPreferences.STATUS_SUCCESS,
                    requested
                )
            )
            assertFalse(
                AutoRootPreferences.exploitFinishAllowed(
                    AutoRootPreferences.STATUS_REBOOT_REQUIRED,
                    requested
                )
            )
        }
        assertFalse(
            AutoRootPreferences.exploitFinishAllowed(
                AutoRootPreferences.STATUS_RUNNING,
                AutoRootPreferences.STATUS_SAFE_FAILURE
            )
        )
        assertFalse(AutoRootPreferences.exploitFinishAllowed(null, "corrupt"))
    }

    @Test
    fun processOutputReaderBoundsAnIndividualLineWhileContinuingToDrain() {
        val captured = mutableListOf<String>()
        val payload = ("x".repeat(100_000) + "\r\nnext").toByteArray()
        RootFlow.consumeBoundedProcessOutput(ByteArrayInputStream(payload), captured::add)
        assertEquals(2, captured.size)
        assertEquals(16 * 1024, captured[0].length)
        assertTrue(captured[0].endsWith("…"))
        assertEquals("next", captured[1])
    }

    @Test
    fun privilegedShellWrapperQuotesEveryByteAsLiteralText() {
        assertEquals("''", RootFlow.shellSingleQuote(""))
        assertEquals(
            "'alpha'\"'\"' beta\n\$(id)'",
            RootFlow.shellSingleQuote("alpha' beta\n\$(id)")
        )
    }

    @Test
    fun stagedUiCleanupKeepsOnlyCurrentSignedAssets() {
        assertFalse(
            RootFlow.shouldPruneStagedUiFile("scr01-home-ui-1.7.27.zip")
        )
        assertFalse(
            RootFlow.shouldPruneStagedUiFile("scr01-overview-bridge-0.4.36.zip")
        )
        assertTrue(
            RootFlow.shouldPruneStagedUiFile("scr01-home-ui-1.7.12.zip")
        )
        assertTrue(
            RootFlow.shouldPruneStagedUiFile("scr01-overview-bridge-0.4.36.zip.new")
        )
        assertFalse(RootFlow.shouldPruneStagedUiFile("manager.apk"))
        assertFalse(RootFlow.shouldPruneStagedUiFile("scr01-home-ui.zip"))
    }

    @Test
    fun systemUiReceiptPinsExactArchiveContents() {
        val receipt = RootFlow.expectedSystemUiReceipt(
            "01234567-89ab-cdef-0123-456789abcdef"
        )
        assertEquals("format=2", receipt.first())
        assertTrue(receipt.contains("scr01_scroot_menu=1.7.27"))
        assertTrue(receipt.contains("scr01_overview_bridge=0.4.36"))
        assertTrue(receipt.any { it.matches(Regex("home_archive=[0-9a-f]{64}")) })
        assertTrue(receipt.any { it.matches(Regex("overview_archive=[0-9a-f]{64}")) })
        assertEquals(receipt.size, receipt.toSet().size)
    }

    @Test
    fun systemUiLiveHealthRequiresBothComponents() {
        assertTrue(RootFlow.SystemUiLiveHealth(true, true).ready)
        assertFalse(RootFlow.SystemUiLiveHealth(true, false).ready)
        assertFalse(RootFlow.SystemUiLiveHealth(false, true).ready)
        assertFalse(RootFlow.SystemUiLiveHealth(false, false).ready)
    }

    @Test
    fun uiRecoveryIsLimitedToComponentActivationFailures() {
        assertTrue(RootFlow.provisionFailureMayUseLiveRecovery(57, false))
        assertTrue(RootFlow.provisionFailureMayUseLiveRecovery(58, false))
        assertTrue(RootFlow.provisionFailureMayUseLiveRecovery(59, false))
        assertFalse(RootFlow.provisionFailureMayUseLiveRecovery(56, false))
        assertFalse(RootFlow.provisionFailureMayUseLiveRecovery(60, false))
        assertFalse(RootFlow.provisionFailureMayUseLiveRecovery(58, true))
    }

    @Test
    fun privateArtifactChecksRejectSymlinksAndDirectories() {
        val directory = Files.createTempDirectory("scroot-artifact-test").toFile()
        val regular = directory.resolve("payload.zip")
        regular.writeText("payload")
        val symlink = directory.resolve("payload-link.zip")
        Files.createSymbolicLink(symlink.toPath(), regular.toPath())

        assertTrue(RootFlow.isRegularFileNoFollow(regular))
        assertFalse(RootFlow.isRegularFileNoFollow(symlink))
        assertFalse(RootFlow.isRegularFileNoFollow(directory))

        symlink.delete()
        regular.delete()
        directory.delete()
    }

    @Test
    fun exactPackageValidationRejectsSplitApks() {
        assertTrue(RootFlow.hasNoSplitApks(null))
        assertTrue(RootFlow.hasNoSplitApks(emptyArray()))
        assertFalse(RootFlow.hasNoSplitApks(arrayOf("/data/app/example/split_config.apk")))
    }
}
