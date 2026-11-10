package dev.sonicbrief.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset

/**
 * iPhone-style motion: directional slides on a fluid spring, no fades.
 *
 * - Drill-in pages push in from the right edge and pop back to the right, with the
 *   previous page returning from a slight left offset (parallax, like iOS navigation).
 * - Sibling tabs switch instantly, like the iOS tab bar.
 * - Expanding sections grow in height while sliding slightly, instead of fading.
 */
object IosMotion {
    /** Settles in ~300 ms with no overshoot — the iOS "fluid, no bounce" feel. */
    private fun offsetSpring() = spring<IntOffset>(
        dampingRatio = 0.9f,
        stiffness = 550f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    private fun sizeSpring() = spring<androidx.compose.ui.unit.IntSize>(
        dampingRatio = 0.9f,
        stiffness = 550f,
        visibilityThreshold = androidx.compose.ui.unit.IntSize.VisibilityThreshold,
    )

    /** Push: the new page slides in from the right edge; the current page stays put. */
    fun pushEnter(): EnterTransition = slideInHorizontally(offsetSpring()) { it }

    /** Pop: the previous page returns from a slight left offset (parallax). */
    fun popEnter(): EnterTransition = slideInHorizontally(offsetSpring()) { -it / 4 }

    /** Pop: the current page slides out to the right edge. */
    fun popExit(): ExitTransition = slideOutHorizontally(offsetSpring()) { it }

    /** Expanding sections grow from the top while sliding slightly — no fade. */
    fun expandEnter(): EnterTransition =
        expandVertically(sizeSpring(), expandFrom = Alignment.Top) +
            slideInVertically(offsetSpring()) { -it / 6 }

    fun collapseExit(): ExitTransition =
        shrinkVertically(sizeSpring(), shrinkTowards = Alignment.Top) +
            slideOutVertically(offsetSpring()) { -it / 6 }

    /** Bottom bar slides up from the bottom edge — no fade. */
    fun barEnter(): EnterTransition = slideInVertically(offsetSpring()) { it }

    fun barExit(): ExitTransition = slideOutVertically(offsetSpring()) { it }
}
