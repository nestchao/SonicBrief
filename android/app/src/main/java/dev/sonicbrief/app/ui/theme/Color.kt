package dev.sonicbrief.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Palette pulled from the app icon: indigo at the top, violet toward the bottom. */
val Indigo500 = Color(0xFF6A55FF)
val Indigo600 = Color(0xFF5B46FB)
val Indigo700 = Color(0xFF4A32D9)
val Violet500 = Color(0xFF8B5CF6)
val Violet400 = Color(0xFFA78BFA)
val Mint500 = Color(0xFF10B39B)
val Amber500 = Color(0xFFF0A020)

val BrandGradient: Brush = Brush.linearGradient(listOf(Indigo500, Violet500))
val BrandGradientSoft: Brush = Brush.linearGradient(listOf(Indigo600, Indigo700))
