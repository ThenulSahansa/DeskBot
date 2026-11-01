package com.example.deskbot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.deskbot.ui.theme.CatppuccinSurface0
import com.example.deskbot.ui.theme.CatppuccinSurface1

/**
 * Flat, modern container card following Catppuccin design principles.
 * Features:
 * - Solid flat surface background (zero translucency or glass blur)
 * - Crisp, clean flat border
 * - Modern rounded geometry
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    backgroundColor: Color = CatppuccinSurface0,
    borderColor: Color = CatppuccinSurface1,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color = backgroundColor, shape = shape)
            .border(width = borderWidth, color = borderColor, shape = shape),
        content = content
    )
}
