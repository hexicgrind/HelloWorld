package ai.sotto.assistant.ui

import ai.sotto.assistant.di.AppContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/** Every destination in the app. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIVE = "live"
    const val ROSTER = "roster"
    const val UPLOAD = "upload"
    const val SETTINGS = "settings"
    const val EARPIECE = "earpiece"
    const val HELP = "help"
    const val DIAGNOSTICS = "diagnostics"

    const val ATTENDEE_DETAIL = "attendee/{attendeeId}"
    fun attendeeDetail(id: String) = "attendee/$id"

    const val ENROLL = "enroll/{attendeeId}"
    fun enroll(id: String) = "enroll/$id"

    const val ARG_ATTENDEE_ID = "attendeeId"
}

/**
 * ViewModel factory that hands the [AppContainer] to whichever view model asks for it.
 * Keeps construction explicit without pulling in an annotation processor.
 */
class SottoViewModelFactory(
    private val container: AppContainer,
    private val creators: Map<Class<out ViewModel>, (AppContainer) -> ViewModel>,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val creator = creators[modelClass]
            ?: creators.entries.firstOrNull { modelClass.isAssignableFrom(it.key) }?.value
            ?: error("No creator registered for $modelClass")
        return creator(container) as T
    }
}
