package org.turnbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository

data class LocationItem(
    val id: String,
    val fullName: String,
    val config: HysteriaConfig? = null
)

sealed class PingsState {
    object Idle : PingsState()
    data class Loading(val lastPings: Map<String, Int?>? = null) : PingsState()
    data class Success(val pings: Map<String, Int?>) : PingsState()
    data class Error(val message: String) : PingsState()
}

class LocationViewModel(
    private val configRepo: HysteriaConfigRepository,
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    var editingConfig by mutableStateOf(HysteriaConfig())
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set


    val isFormValid: Boolean
        get() = nameError == null && serverError == null && keyError == null &&
                editingName.isNotBlank() && editingConfig.id.isNotBlank() &&
                editingConfig.key.isNotBlank()


    init {
        loadLocations()
    }

    fun loadLocations() {
        viewModelScope.launch {
            val savedConfigs = configRepo.getAllHysteriaConfigs()
            val currentSelectedId = configRepo.getSelectedHysteriaId()

            locations.clear()

            savedConfigs.forEach { (id, config) ->
                val normalized = config.normalized()
                locations.add(LocationItem(id, normalized.displayName(), normalized))
            }

            if (locations.isNotEmpty() && (currentSelectedId.isBlank() || locations.none { it.id == currentSelectedId })) {
                val nextId = locations.firstOrNull()?.id
                configRepo.setSelectedHysteriaId(nextId ?: "")
                selectedLocationId = nextId
            } else {
                selectedLocationId = currentSelectedId.ifBlank { null }
            }
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            configRepo.setSelectedHysteriaId(id)
            selectedLocationId = id
            onComplete()
        }
    }

    fun refreshPings(performPing: suspend (HysteriaConfig) -> Long?) {
        val previousPings = (pingsState as? PingsState.Success)?.pings
        viewModelScope.launch {
            pingsState = PingsState.Loading(lastPings = previousPings)
            try {
                val results = locations.map { location ->
                    async {
                        val config = location.config ?: return@async location.id to null
                        val result = performPing(config)
                        location.id to result?.toInt()
                    }
                }.awaitAll().toMap()

                pingsState = PingsState.Success(results)
            } catch (e: Exception) {
                pingsState = PingsState.Error(e.message ?: "Error")
            }
        }
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        isSaving = false

        if (id == null) {
            editingId = null
            editingConfig = HysteriaConfig()
            editingName = ""
        } else {
            val location = locations.find { it.id == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: HysteriaConfig()
            editingName = editingConfig.displayName()
        }
    }


    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onSniChanged(value: String) {
        // Kept for compatibility with older callers. olcRTC locations no longer use SNI.
    }

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    fun onBypassProviderChanged(value: String) {
        editingConfig = editingConfig.copy(
            bypassProvider = HysteriaConfig.normalizeProvider(value)
        )
    }


    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 30 -> "Name is too long (max 30 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        serverError = when {
            server.isBlank() -> "Room ID cannot be empty"
            server.length > 256 -> "Room ID is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !key.matches(Regex("^[a-fA-F0-9]{64}$")) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        validateName(editingName)
        validateServer(editingConfig.id)
        validateKey(editingConfig.key)

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true
            val id = editingId ?: "custom_${(100..999).random()}"
            val finalConfig = editingConfig.copy(name = editingName).normalized()
            configRepo.saveHysteriaConfig(finalConfig, id)
            configRepo.setSelectedHysteriaId(id)
            loadLocations()
            delay(600)
            onComplete()
            isSaving = false
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            configRepo.deleteHysteriaConfig(id)
            loadLocations()
            onComplete()
        }
    }
}
