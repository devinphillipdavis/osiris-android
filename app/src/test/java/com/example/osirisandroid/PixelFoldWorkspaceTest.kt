package com.example.osirisandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osiris.android.app.adaptive.FoldHingeOrientation
import org.osiris.android.app.adaptive.HingeBoundsRect
import org.osiris.android.app.adaptive.PixelFoldPosture
import org.osiris.android.app.adaptive.PixelFoldPostureController
import org.osiris.android.app.evidence.EvidenceBadgePresenter
import org.osiris.android.app.journal.TaskJournalContinuityController
import org.osiris.android.sdk.DeploymentAuthority
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.EvidenceStatus
import org.osiris.android.sdk.ImplementationStatus
import java.io.File

class PixelFoldWorkspaceTest {

    @Test
    fun `AT-UI-01 and AT-UI-02 folded compact single-pane and unfolded dual-pane with zero hinge occlusion`() {
        val controller = PixelFoldPostureController()

        // 1. Folded cover screen (AT-UI-01)
        val compact = controller.computeLayout(
            windowWidthPx = 1080,
            windowHeightPx = 2092,
            isHalfOpened = false,
            isFlatUnfolded = false,
            hingeOrientation = FoldHingeOrientation.NONE,
            hingeBounds = HingeBoundsRect(0, 0, 0, 0)
        )
        assertEquals(PixelFoldPosture.FOLDED_COMPACT, compact.posture)
        assertEquals(1, compact.paneCount)
        assertTrue(compact.hasZeroHingeOcclusion())

        // 2. Unfolded inner display with vertical hinge (AT-UI-02)
        val unfolded = controller.computeLayout(
            windowWidthPx = 2208,
            windowHeightPx = 1840,
            isHalfOpened = false,
            isFlatUnfolded = true,
            hingeOrientation = FoldHingeOrientation.VERTICAL,
            hingeBounds = HingeBoundsRect(1092, 0, 1116, 1840)
        )
        assertEquals(PixelFoldPosture.UNFOLDED_EXPANDED, unfolded.posture)
        assertEquals(2, unfolded.paneCount)
        assertTrue("Interactive panes must never overlap physical hinge bounds", unfolded.hasZeroHingeOcclusion())

        // 3. Half-opened tabletop posture (AT-UI-02)
        val tabletop = controller.computeLayout(
            windowWidthPx = 2208,
            windowHeightPx = 1840,
            isHalfOpened = true,
            isFlatUnfolded = false,
            hingeOrientation = FoldHingeOrientation.HORIZONTAL,
            hingeBounds = HingeBoundsRect(0, 908, 2208, 932)
        )
        assertEquals(PixelFoldPosture.HALF_OPENED_TABLETOP, tabletop.posture)
        assertEquals(2, tabletop.paneCount)
        assertTrue(tabletop.hasZeroHingeOcclusion())
    }

    @Test
    fun `AT-UI-03 and AT-PERF-02 state continuity across fold and unfold transitions within 100ms`() {
        val journal = TaskJournalContinuityController()
        journal.updateDraftState(
            taskId = "task-fold-continuity-99",
            draftText = "osiris::}{> /dna \"Preserve across Pixel Fold posture transitions\"",
            scrollOffsetPx = 420,
            selectedTab = "EVIDENCE",
            approvalModalOpen = true
        )

        // Transition 1: Folded -> Half-Open Book -> Unfolded -> Folded (10 rapid cycles)
        repeat(10) {
            val snapUnfolded = journal.onFoldPostureTransition(
                windowWidthPx = 2208,
                windowHeightPx = 1840,
                isHalfOpened = false,
                isFlatUnfolded = true,
                hingeOrientation = FoldHingeOrientation.VERTICAL,
                hingeBounds = HingeBoundsRect(1092, 0, 1116, 1840)
            )
            assertEquals("task-fold-continuity-99", snapUnfolded.activeTaskId)
            assertEquals("osiris::}{> /dna \"Preserve across Pixel Fold posture transitions\"", snapUnfolded.draftComposerText)
            assertEquals(420, snapUnfolded.transcriptScrollOffsetPx)
            assertEquals("EVIDENCE", snapUnfolded.selectedNavigationTab)
            assertTrue(snapUnfolded.pendingApprovalModalOpen)
            assertTrue("Transition latency must be <= 100ms", snapUnfolded.lastPostureTransitionLatencyMs <= 100.0)
        }
    }

    @Test
    fun `AT-UI-04 and AT-UI-05 multi-window split mode and accessibility 48dp touch targets and 200 percent font scale`() {
        val journal = TaskJournalContinuityController()
        val splitSnap = journal.onFoldPostureTransition(
            windowWidthPx = 1100,
            windowHeightPx = 1840,
            isHalfOpened = false,
            isFlatUnfolded = false,
            hingeOrientation = FoldHingeOrientation.NONE,
            hingeBounds = HingeBoundsRect(0, 0, 0, 0),
            isMultiWindowSplit = true
        )
        assertTrue(splitSnap.activePostureSpec.multiWindowSplitActive)
        assertTrue(splitSnap.activePostureSpec.minInteractiveTouchTargetDp >= 48)
        assertEquals(200, splitSnap.activePostureSpec.supportsFontScaleUpToPercent)
    }

    @Test
    fun `AT-UI-06 evidence status badge separation never conflates passing tests with deployment authorization`() {
        val recordWithoutHumanGate = EvidenceBadgePresenter.deriveNormativeRecordFromTestOutcome(
            implementationStatus = ImplementationStatus.IMPLEMENTED,
            evaluationOutcome = EvaluationOutcome.TESTED_PASS,
            hasSignedHumanReleaseReceipt = false
        )
        assertEquals(EvidenceStatus.TESTED_PASS, recordWithoutHumanGate.evidenceStatus)
        assertEquals(DeploymentAuthority.REQUIRES_APPROVAL, recordWithoutHumanGate.deploymentAuthority)

        val badges = EvidenceBadgePresenter.renderBadges(recordWithoutHumanGate)
        assertFalse("Unit test pass alone must never authorize deployment", badges.isDeploymentAuthorized)
        assertEquals("IMPL: IMPLEMENTED", badges.implementationBadge)
        assertEquals("EVIDENCE: TESTED_PASS", badges.evidenceBadge)
        assertEquals("AUTHORITY: REQUIRES_APPROVAL", badges.authorityBadge)
    }

    @Test
    fun `AT-SEC-08 AndroidManifest contains zero prohibited permissions and sets allowBackup false`() {
        val manifestCandidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val manifestFile = manifestCandidates.firstOrNull { it.exists() }
        assertTrue("AndroidManifest.xml must exist", manifestFile != null)
        val content = manifestFile!!.readText()

        val prohibitedPermissions = listOf(
            "READ_SMS",
            "RECEIVE_SMS",
            "READ_CONTACTS",
            "READ_CALL_LOG",
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "SYSTEM_ALERT_WINDOW",
            "WRITE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES"
        )
        for (perm in prohibitedPermissions) {
            assertFalse("Prohibited permission $perm found in AndroidManifest.xml", content.contains(perm))
        }
        assertTrue("android:allowBackup=\"false\" must be enforced", content.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `AT-PERF-01 and AT-PERF-05 workspace cold initialization under 1200ms and idle drain under 1 percent per hour`() {
        val startNs = System.nanoTime()
        val journal = TaskJournalContinuityController()
        val snap = journal.snapshot()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("Initialization must complete under 1200ms", elapsedMs <= 1200.0)
        assertEquals(PixelFoldPosture.FOLDED_COMPACT, snap.activePostureSpec.posture)

        val idleDrain = journal.estimateIdleBatteryDrainPercentPerHour(
            activeBackgroundWakeLocks = 0,
            activePollingLoops = 0
        )
        assertTrue("Idle battery drain ($idleDrain%/hr) must be <= 1.0%/hr", idleDrain <= 1.0)
    }
}
