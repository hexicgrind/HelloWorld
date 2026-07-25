package ai.sotto.assistant.ui.components

import ai.sotto.assistant.R
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.ui.theme.SottoColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The Sotto mark, for headers and empty states. */
@Composable
fun SottoMark(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Icon(
        painter = painterResource(R.drawable.ic_sotto_wordmark),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = modifier.size(size),
    )
}

/** A titled card. The basic unit of every settings-ish screen. */
@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/** Severity of an inline status message. */
enum class NoticeTone { INFO, SUCCESS, WARNING, ERROR }

@Composable
fun NoticeTone.color(): Color = when (this) {
    NoticeTone.INFO -> MaterialTheme.colorScheme.primary
    NoticeTone.SUCCESS -> MaterialTheme.colorScheme.secondary
    NoticeTone.WARNING -> MaterialTheme.colorScheme.tertiary
    NoticeTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
fun NoticeTone.icon(): ImageVector = when (this) {
    NoticeTone.INFO -> Icons.Rounded.Info
    NoticeTone.SUCCESS -> Icons.Rounded.CheckCircle
    NoticeTone.WARNING -> Icons.Rounded.WarningAmber
    NoticeTone.ERROR -> Icons.Rounded.ErrorOutline
}

/**
 * An inline message with an optional action. Every error the user can hit gets one of
 * these, with a plain-language cause and a concrete next step.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val accent = tone.color()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                tone.icon(),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onAction)
                            .padding(vertical = 4.dp),
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Renders an [AppError] with its recovery hint. */
@Composable
fun ErrorNotice(
    error: AppError?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        error?.let {
            Notice(
                text = it.userMessage,
                detail = it.recovery,
                tone = NoticeTone.ERROR,
                actionLabel = actionLabel,
                onAction = onAction,
                onDismiss = onDismiss,
            )
        }
    }
}

/** Small status chip: a dot plus a word. Used for the four pipeline health readouts. */
@Composable
fun StatusPill(
    label: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val accent = tone.color()
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        value
    } else {
        1f
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = accent.copy(alpha = 0.13f),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

/** Full-width guidance for an empty screen: what this is, and the one thing to do next. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** Circular avatar showing an attendee's initials, tinted per-person but consistently. */
@Composable
fun InitialsAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    highlighted: Boolean = false,
) {
    val palette = listOf(
        SottoColors.Violet, SottoColors.Teal, SottoColors.Amber,
        SottoColors.VioletBright, SottoColors.TealDeep,
    )
    val accent = if (highlighted) {
        MaterialTheme.colorScheme.secondary
    } else {
        palette[(initials.hashCode().mod(palette.size))]
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(
                width = if (highlighted) 2.dp else 0.dp,
                color = if (highlighted) accent else Color.Transparent,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

/** Centred spinner with a label — used wherever a wait exceeds a moment. */
@Composable
fun LoadingBlock(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A horizontal rule that fades at both ends. */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .clearAndSetSemantics { }
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.outline,
                        Color.Transparent,
                    )
                )
            )
    )
}

/** Rounded rectangle used for the whisper card and other hero surfaces. */
val HeroShape = RoundedCornerShape(26.dp)
