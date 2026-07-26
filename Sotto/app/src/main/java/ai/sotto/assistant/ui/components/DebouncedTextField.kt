package ai.sotto.assistant.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay

/**
 * A text field that stays responsive when its value is persisted asynchronously.
 *
 * The problem this solves: if a field's `value` comes back from DataStore (or any other
 * async round-trip), every keystroke races the write. The user types, the field is still
 * showing the value from two frames ago, Compose resets the text — and with it the
 * cursor — so characters land out of order or overwrite each other.
 *
 * The fix is to make the local edit buffer the single source of truth while the user is
 * typing, and push outward on a debounce. [external] is only adopted before the first
 * edit, which covers the real case of a stored value arriving after first composition
 * without ever yanking text out from under the person typing.
 */
@Composable
fun DebouncedTextField(
    external: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    maxLength: Int? = null,
    debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle? = null,
) {
    var text by remember { mutableStateOf(external) }
    var edited by remember { mutableStateOf(false) }

    // Adopt a value that arrived late (settings loading from disk), but never once the
    // user has started typing — that would be the very jump we're trying to prevent.
    LaunchedEffect(external) {
        if (!edited && external != text) text = external
    }

    LaunchedEffect(text) {
        if (!edited || text == external) return@LaunchedEffect
        delay(debounceMs)
        onCommit(text)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            edited = true
            text = if (maxLength != null) next.take(maxLength) else next
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label?.let { { androidx.compose.material3.Text(it) } },
        placeholder = placeholder?.let { { androidx.compose.material3.Text(it) } },
        supportingText = supportingText?.let { { androidx.compose.material3.Text(it) } },
        singleLine = singleLine,
        shape = MaterialTheme.shapes.medium,
        textStyle = textStyle ?: MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        leadingIcon = leadingIcon,
    )
}

/** 350 ms: long enough to coalesce a burst of typing, short enough to feel instant. */
const val DEFAULT_DEBOUNCE_MS = 350L

/** Searching wants tighter feedback than a settings field. */
const val SEARCH_DEBOUNCE_MS = 120L
