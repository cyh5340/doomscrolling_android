package com.doomscroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/** Content is capped so tablets get a readable column instead of edge-to-edge text. */
val MaxContentWidth = 640.dp

object AppIcons {
    val back: Painter @Composable get() = painterResource(R.drawable.ic_back)
    val more: Painter @Composable get() = painterResource(R.drawable.ic_more)
    val share: Painter @Composable get() = painterResource(R.drawable.ic_share)
    val add: Painter @Composable get() = painterResource(R.drawable.ic_add)
    val chevron: Painter @Composable get() = painterResource(R.drawable.ic_chevron)
}

/** Standard screen: collapsing large title, optional back arrow, scrolling content column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(AppIcons.back, contentDescription = "Back") }
                },
                actions = actions,
                scrollBehavior = scroll,
                // Same color when scrolled: on tablets the bar is narrower than the screen.
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = MaxContentWidth + 8.dp),
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .wrapContentWidth().widthIn(max = MaxContentWidth).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            Spacer(Modifier.height(88.dp)) // room for a FAB and the gesture bar
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Warning card with one action. */
@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}, extra: @Composable ColumnScope.() -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            extra()
            if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

private val avatarColors = listOf(
    Color(0xFFFF7A45), Color(0xFF8C7CFF), Color(0xFF4ADE9B), Color(0xFFF5B83D),
    Color(0xFF4CB8F5), Color(0xFFF06292), Color(0xFF26C6DA),
)

/** Initial on a color picked from the name, so each friend keeps the same color. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(avatarColors[name.hashCode().absoluteValue % avatarColors.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color(0xFF14151A),
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        Modifier.background(container, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = content,
    )
}

/** Emoji in a tinted circle: used for onboarding steps and info rows. */
@Composable
fun EmojiBadge(emoji: String, size: Dp = 44.dp, container: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(Modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun InfoRow(emoji: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        EmojiBadge(emoji, container = MaterialTheme.colorScheme.surfaceContainerHigh)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

// ponytail: USD only; take a currency code from the group if stakes go international.
fun formatMoney(cents: Int): String =
    if (cents % 100 == 0) "$${cents / 100}" else "$%d.%02d".format(cents / 100, cents % 100)
