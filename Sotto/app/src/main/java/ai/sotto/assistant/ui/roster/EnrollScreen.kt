package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.live.CameraSurface
import ai.sotto.assistant.ui.theme.SottoColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.sotto.assistant.ui.scaffold.SottoTopBar

/**
 * Face enrolment: capture a few looks at someone so Sotto can recognise them later.
 *
 * The design doc treats embeddings as precomputed inputs; this is how they actually get
 * computed. Several samples are taken and averaged, because a single frame bakes in
 * whatever lighting and angle happened at that instant.
 */
@Composable
fun EnrollScreen(
    attendeeId: String,
    viewModel: RosterViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enrol by viewModel.enrol.collectAsStateWithLifecycle()
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // Set by the capture button, consumed by the next camera frame. Copying every frame
    // "just in case" would churn ~35 MB/s of bitmaps for no reason; this way the copy
    // only happens on the frame the user actually asked for.
    val capturePending = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    LaunchedEffect(attendeeId) { viewModel.beginEnrolment(attendeeId) }
    DisposableEffect(Unit) { onDispose { viewModel.endEnrolment() } }

    // Multiple, not single. The single-item picker meant someone working from a photo
    // library had to leave and re-enter the picker for every sample, and on a
    // memory-tight phone the round trip could recreate the activity and lose what was
    // already captured — so in practice they could never get past one photo.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RosterViewModel.MAX_SAMPLES)
    ) { uris -> viewModel.captureFromUris(uris) }

    LaunchedEffect(enrol.saved) {
        if (enrol.saved) {
            kotlinx.coroutines.delay(900)
            onBack()
        }
    }

    val attendee = enrol.attendee

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = SottoColors.Ink,
        topBar = {
            SottoTopBar(
                title = attendee?.name?.let { "Enrol $it" } ?: "Enrol face",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }) {
                        Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch camera")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "Get their face filling most of the frame and capture " +
                    "${RosterViewModel.MIN_SAMPLES} times, changing the angle slightly between " +
                    "each one — that's what makes recognition reliable. No camera access to " +
                    "them? Tap the photo icon and pick several pictures at once.",
                style = MaterialTheme.typography.bodyMedium,
                color = SottoColors.Slate,
            )

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SottoColors.InkElevated),
            ) {
                CameraSurface(
                    onFrame = { bitmap, _, _ ->
                        if (capturePending.compareAndSet(true, false)) {
                            // captureFromFrame copies before the analyzer recycles this.
                            viewModel.captureFromFrame(bitmap)
                        }
                    },
                    lensFacing = lensFacing,
                    modifier = Modifier.fillMaxSize(),
                )
                // Framing guide.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.62f)
                        .aspectRatio(0.78f)
                        .border(2.dp, SottoColors.Cloud.copy(alpha = 0.45f), RoundedCornerShape(120.dp))
                )
            }

            Spacer(Modifier.height(14.dp))

            if (enrol.samples.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(enrol.samples) { index, sample ->
                        SampleThumb(
                            sample = sample,
                            onRemove = { viewModel.removeSample(index) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            enrol.message?.let { message ->
                Notice(
                    text = message,
                    tone = if (enrol.saved) NoticeTone.SUCCESS else NoticeTone.INFO,
                )
                Spacer(Modifier.height(10.dp))
            }

            ErrorNotice(error = enrol.error)

            Spacer(Modifier.weight(1f))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = CircleShape,
                    color = SottoColors.InkElevated,
                    modifier = Modifier.size(54.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = "Use a photo instead",
                            tint = SottoColors.Slate,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }

                Box(Modifier.weight(1f)) {
                    PrimaryButton(
                        text = "Capture (${enrol.samples.size}/${RosterViewModel.MIN_SAMPLES})",
                        icon = Icons.Rounded.CenterFocusStrong,
                        onClick = { capturePending.set(true) },
                        enabled = !enrol.capturing &&
                            enrol.samples.size < RosterViewModel.MAX_SAMPLES,
                        loading = enrol.capturing,
                    )
                }
            }

            PrimaryButton(
                text = when {
                    enrol.saved -> "Saved"
                    enrol.samples.isEmpty() -> "Save face"
                    enrol.isReliable -> "Save face"
                    // Say plainly that saving now is allowed but weaker, rather than
                    // greying the button out and leaving the user stuck.
                    else -> "Save face (${enrol.samples.size} of ${RosterViewModel.MIN_SAMPLES})"
                },
                onClick = viewModel::saveEnrolment,
                enabled = enrol.canSave && !enrol.saved,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SampleThumb(
    sample: RosterViewModel.EnrolState.Sample,
    onRemove: () -> Unit,
) {
    Box {
        val preview = sample.preview
        if (preview != null && !preview.isRecycled) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SottoColors.InkSurface)
            )
        }
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = SottoColors.Ink.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove this capture",
                    tint = SottoColors.Cloud,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            "${(sample.quality * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = if (sample.quality > 0.5f) SottoColors.Teal else SottoColors.Amber,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 3.dp),
        )
    }
}
