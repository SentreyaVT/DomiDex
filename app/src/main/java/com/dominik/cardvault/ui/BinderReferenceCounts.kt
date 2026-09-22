package com.dominik.cardvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.dominik.cardvault.model.CollectionRepository
import kotlinx.coroutines.CancellationException

/** The reader may cache new variant slots without changing the overview's set names. */
@Composable
internal fun rememberBinderReferenceCounts(
    repository: CollectionRepository,
    setNames: List<String>,
    selectedSetName: String?,
): State<Map<String, Int>> = produceState(emptyMap(), repository, setNames, selectedSetName) {
    // Retain the last overview values while reading; reload as soon as it is shown again.
    if (selectedSetName == null) {
        value = setNames.associateWith { setName ->
            try {
                repository.binderReferenceCount(setName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                0
            }
        }
    }
}
