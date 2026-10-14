package org.osiris.android.app.adaptive

import kotlinx.serialization.Serializable

@Serializable
enum class PixelFoldPosture {
    FOLDED_COMPACT,
    HALF_OPENED_TABLETOP,
    HALF_OPENED_BOOK,
    UNFOLDED_EXPANDED
}

@Serializable
enum class FoldHingeOrientation {
    NONE,
    HORIZONTAL,
    VERTICAL
}

@Serializable
data class HingeBoundsRect(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int
) {
    val widthPx: Int get() = (rightPx - leftPx).coerceAtLeast(0)
    val heightPx: Int get() = (bottomPx - topPx).coerceAtLeast(0)

    fun intersects(other: HingeBoundsRect): Boolean {
        return leftPx < other.rightPx &&
            rightPx > other.leftPx &&
            topPx < other.bottomPx &&
            bottomPx > other.topPx
    }
}

@Serializable
data class AdaptiveLayoutSpec(
    val posture: PixelFoldPosture,
    val paneCount: Int,
    val hingeExclusionBounds: HingeBoundsRect,
    val primaryPaneBounds: HingeBoundsRect,
    val secondaryPaneBounds: HingeBoundsRect?,
    val minInteractiveTouchTargetDp: Int,
    val supportsFontScaleUpToPercent: Int,
    val multiWindowSplitActive: Boolean
) {
    fun hasZeroHingeOcclusion(): Boolean {
        if (hingeExclusionBounds.widthPx == 0 && hingeExclusionBounds.heightPx == 0) return true
        val primaryOverlap = primaryPaneBounds.intersects(hingeExclusionBounds)
        val secondaryOverlap = secondaryPaneBounds?.intersects(hingeExclusionBounds) ?: false
        return !primaryOverlap && !secondaryOverlap
    }
}

/**
 * Pixel Fold Adaptive Posture State Machine (Section 7, AT-UI-01..05, AT-PERF-02).
 * Computes hinge-aware dual-pane/tabletop/compact layout bounds and enforces >=48dp touch targets.
 */
class PixelFoldPostureController {

    var currentLayout: AdaptiveLayoutSpec = computeLayout(
        windowWidthPx = 1080,
        windowHeightPx = 2092,
        isHalfOpened = false,
        isFlatUnfolded = false,
        hingeOrientation = FoldHingeOrientation.NONE,
        hingeBounds = HingeBoundsRect(0, 0, 0, 0),
        isMultiWindowSplit = false
    )
        private set

    fun computeLayout(
        windowWidthPx: Int,
        windowHeightPx: Int,
        isHalfOpened: Boolean,
        isFlatUnfolded: Boolean,
        hingeOrientation: FoldHingeOrientation,
        hingeBounds: HingeBoundsRect,
        isMultiWindowSplit: Boolean = false
    ): AdaptiveLayoutSpec {
        val posture = when {
            isHalfOpened && hingeOrientation == FoldHingeOrientation.HORIZONTAL ->
                PixelFoldPosture.HALF_OPENED_TABLETOP
            isHalfOpened && hingeOrientation == FoldHingeOrientation.VERTICAL ->
                PixelFoldPosture.HALF_OPENED_BOOK
            isFlatUnfolded || windowWidthPx >= 1800 ->
                PixelFoldPosture.UNFOLDED_EXPANDED
            else ->
                PixelFoldPosture.FOLDED_COMPACT
        }

        val spec = when (posture) {
            PixelFoldPosture.FOLDED_COMPACT -> AdaptiveLayoutSpec(
                posture = posture,
                paneCount = 1,
                hingeExclusionBounds = HingeBoundsRect(0, 0, 0, 0),
                primaryPaneBounds = HingeBoundsRect(0, 0, windowWidthPx, windowHeightPx),
                secondaryPaneBounds = null,
                minInteractiveTouchTargetDp = 48,
                supportsFontScaleUpToPercent = 200,
                multiWindowSplitActive = isMultiWindowSplit
            )
            PixelFoldPosture.HALF_OPENED_TABLETOP -> {
                val topBottom = hingeBounds.topPx.coerceAtMost(windowHeightPx / 2)
                val bottomTop = hingeBounds.bottomPx.coerceAtLeast(topBottom)
                AdaptiveLayoutSpec(
                    posture = posture,
                    paneCount = 2,
                    hingeExclusionBounds = hingeBounds,
                    primaryPaneBounds = HingeBoundsRect(0, 0, windowWidthPx, topBottom),
                    secondaryPaneBounds = HingeBoundsRect(0, bottomTop, windowWidthPx, windowHeightPx),
                    minInteractiveTouchTargetDp = 48,
                    supportsFontScaleUpToPercent = 200,
                    multiWindowSplitActive = isMultiWindowSplit
                )
            }
            PixelFoldPosture.HALF_OPENED_BOOK, PixelFoldPosture.UNFOLDED_EXPANDED -> {
                val leftRight = if (hingeBounds.widthPx > 0) hingeBounds.leftPx else (windowWidthPx / 2) - 12
                val rightLeft = if (hingeBounds.widthPx > 0) hingeBounds.rightPx else (windowWidthPx / 2) + 12
                val effectiveHinge = if (hingeBounds.widthPx > 0) {
                    hingeBounds
                } else {
                    HingeBoundsRect(leftRight, 0, rightLeft, windowHeightPx)
                }
                AdaptiveLayoutSpec(
                    posture = posture,
                    paneCount = 2,
                    hingeExclusionBounds = effectiveHinge,
                    primaryPaneBounds = HingeBoundsRect(0, 0, effectiveHinge.leftPx, windowHeightPx),
                    secondaryPaneBounds = HingeBoundsRect(effectiveHinge.rightPx, 0, windowWidthPx, windowHeightPx),
                    minInteractiveTouchTargetDp = 48,
                    supportsFontScaleUpToPercent = 200,
                    multiWindowSplitActive = isMultiWindowSplit
                )
            }
        }
        currentLayout = spec
        return spec
    }
}
