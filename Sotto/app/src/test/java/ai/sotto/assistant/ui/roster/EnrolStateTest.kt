package ai.sotto.assistant.ui.roster

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The gate on saving an enrolment.
 *
 * This used to require [RosterViewModel.MIN_SAMPLES] before the Save button would light
 * up. For anyone working from a photo library rather than standing in front of the
 * person, that was an unpassable wall — one photo of someone is the normal case, and it
 * blocked face matching entirely. Three samples is still the recommendation, because
 * averaging cancels out the lighting and angle of any single frame. It is no longer a
 * precondition.
 */
class EnrolStateTest {

    private fun sample(quality: Float = 0.8f) =
        RosterViewModel.EnrolState.Sample(FloatArray(128) { 0.1f }, quality, null)

    @Test
    fun `a single sample is enough to save`() {
        val state = RosterViewModel.EnrolState(samples = listOf(sample()))
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `no samples cannot be saved`() {
        assertThat(RosterViewModel.EnrolState().canSave).isFalse()
    }

    @Test
    fun `saving is blocked while a capture is still running`() {
        val state = RosterViewModel.EnrolState(samples = listOf(sample()), capturing = true)
        assertThat(state.canSave).isFalse()
    }

    @Test
    fun `one or two samples save but are not called reliable`() {
        assertThat(RosterViewModel.EnrolState(samples = listOf(sample())).isReliable).isFalse()
        assertThat(RosterViewModel.EnrolState(samples = List(2) { sample() }).isReliable).isFalse()
    }

    @Test
    fun `three samples is the point at which averaging is doing its job`() {
        val state = RosterViewModel.EnrolState(samples = List(3) { sample() })
        assertThat(state.isReliable).isTrue()
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `average quality reflects every sample`() {
        val state = RosterViewModel.EnrolState(
            samples = listOf(sample(0.2f), sample(0.4f), sample(0.6f)),
        )
        assertThat(state.averageQuality).isWithin(1e-4f).of(0.4f)
    }

    @Test
    fun `average quality of nothing is zero rather than a crash`() {
        assertThat(RosterViewModel.EnrolState().averageQuality).isEqualTo(0f)
    }

    @Test
    fun `samples are identified by instance so duplicates both survive`() {
        // Two captures of the same still would otherwise collapse into one, because a
        // data class compares FloatArrays by reference anyway and quality would tie.
        val first = sample(0.5f)
        val second = sample(0.5f)
        val state = RosterViewModel.EnrolState(samples = listOf(first, second))
        assertThat(state.samples).hasSize(2)
        assertThat(first).isNotEqualTo(second)
        assertThat(first).isEqualTo(first)
    }
}
