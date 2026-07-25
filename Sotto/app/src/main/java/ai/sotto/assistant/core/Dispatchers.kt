package ai.sotto.assistant.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable dispatchers so every coroutine in the app is testable. */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher

    companion object {
        val Default: DispatcherProvider = object : DispatcherProvider {
            override val main get() = Dispatchers.Main
            override val io get() = Dispatchers.IO
            override val default get() = Dispatchers.Default
        }
    }
}
