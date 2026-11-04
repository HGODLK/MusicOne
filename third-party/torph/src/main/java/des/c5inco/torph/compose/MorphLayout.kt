package des.c5inco.torph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Sizes the node to the morphing text (animated per [MorphOptions.sizeMode]) and draws it.
 * Set [TextMorphState.text], [TextMorphState.style] and [TextMorphState.options] from composition;
 * this modifier performs measurement and diffing in the layout pass and reads animatables only in
 * layout and draw, never in composition.
 *
 * The drawing gets its own layer: without one, anything else redrawing in the surrounding layer (a
 * ticking clock next to it, say) re-records every segment, and every animation frame here re-records
 * everything around it.
 */
public fun Modifier.textMorph(state: TextMorphState): Modifier = this
    .layout { measurable, constraints ->
        val size = state.measure(constraints, layoutDirection)
        val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
        layout(size.width, size.height) { placeable.place(0, 0) }
    }
    .graphicsLayer()
    .drawBehind { state.draw(this) }
