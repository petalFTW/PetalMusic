/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Radius is per component type, not one value everywhere: covers are records (nearly
 * square), artists are round, floating things are pills, sheets are soft-topped.
 */
object Radius {
    val sleeve = RoundedCornerShape(3.dp)
    val sleeveHero = RoundedCornerShape(6.dp)
    val tile = RoundedCornerShape(2.dp)
    val control = RoundedCornerShape(14.dp)
    val menu = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** The player's control deck: a sheet that floats, so all four corners. */
    val deck = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
    val circle = CircleShape
}

/** 4dp grid. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val huge = 64.dp
    val gutter = 20.dp
    val section = 32.dp
}

object Layer {
    const val PAGE = 0f
    const val STICKY = 10f
    const val CHROME = 20f
    const val PLAYER = 30f
    const val DRAWER = 40f
    const val MENU = 50f
    const val TOAST = 60f
}
