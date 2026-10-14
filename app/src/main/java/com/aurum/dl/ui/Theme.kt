package com.aurum.dl.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** くすみグリーン × ベージュのパレット。 */
object Aurum {
    val Bg = Color(0xFFF1EBDD)         // 生成りのベージュ (背景)
    val Card = Color(0xFFF9F5EB)       // カード
    val CardHigh = Color(0xFFE8E0CC)   // 選択前のチップ・強調面
    val Tint = Color(0xFFDFE6D5)       // ごく淡いグリーン (強調カード)
    val Line = Color(0xFFD9CFB6)       // 罫線・トラック

    val Sage = Color(0xFF7E9876)       // くすみグリーン (主役)
    val SageSoft = Color(0xFF9DB295)
    val SageDeep = Color(0xFF55704F)   // 文字に使う濃いめのグリーン
    val OnSage = Color(0xFFFBF8EF)     // グリーン面の上の文字

    val Text = Color(0xFF3C463A)
    val Muted = Color(0xFF8D907D)
    val Danger = Color(0xFFB5705E)     // くすんだテラコッタ

    val sageBrush: Brush = Brush.horizontalGradient(listOf(Sage, SageSoft))
}

@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Aurum.Sage,
            onPrimary = Aurum.OnSage,
            secondary = Aurum.SageDeep,
            background = Aurum.Bg,
            onBackground = Aurum.Text,
            surface = Aurum.Card,
            onSurface = Aurum.Text,
            outline = Aurum.Line,
            error = Aurum.Danger,
        ),
        content = content,
    )
}
