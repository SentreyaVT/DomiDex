package com.dominik.cardvault.data.sharing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal data class SharedBinderViewState(
    val binder: SharedBinderPage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Each visible-page subscription owns its requests; stopping it cancels HTTP and forgets the page. */
internal fun observeSharedBinderPage(
    gateway: SharedBinderGateway,
    token: String,
    page: Int,
    pollMillis: Long = 15_000,
): Flow<SharedBinderViewState> = flow {
    emit(SharedBinderViewState(loading = true))
    while (true) {
        currentCoroutineContext().ensureActive()
        var unavailable = false
        val state = try {
            SharedBinderViewState(binder = gateway.load(token, page))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SharedBinderReadException) {
            unavailable = failure.unavailable
            SharedBinderViewState(error = failure.message)
        } catch (_: Exception) {
            SharedBinderViewState(error = SharedBinderReadException().message)
        }
        currentCoroutineContext().ensureActive()
        emit(state)
        if (unavailable) return@flow
        delay(pollMillis.coerceAtLeast(1))
    }
}
