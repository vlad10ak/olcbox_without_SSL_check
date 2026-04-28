package org.turnbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.turnbox.app.data.importer.ConfigImporter
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.model.ProviderConfig
import org.turnbox.app.data.model.TurnConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository
import org.turnbox.app.ui.features.locations.LocationItem
import org.turnbox.app.vpn.VpnManager

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val configRepo: HysteriaConfigRepository,
    private val configImporter: ConfigImporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        UiState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = HysteriaConfig(),
            turnData = TurnConfig(),
            selectedTurnType = HysteriaConfig.DEFAULT_BYPASS_PROVIDER,
            shouldShowConfigInvalidReminder = false,
            providers = emptyList()
        )
    )
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs

    init {
        loadProviders()
        loadCurrentConfig()

        viewModelScope.launch {
            vpnManager.isConnected.collect { connected ->
                _state.update { it.copy(isVpnConnected = connected, isVpnLoading = false) }
            }
        }
    }

    private fun loadProviders() {
        val hardcodedProviders = listOf(
            ProviderConfig(id = 1, code = HysteriaConfig.PROVIDER_JAZZ, name = "Jazz"),
            ProviderConfig(id = 2, code = HysteriaConfig.PROVIDER_TELEMOST, name = "Telemost"),
            ProviderConfig(id = 3, code = HysteriaConfig.PROVIDER_WB_STREAM, name = "WB Stream")
        )

        _state.update { it.copy(providers = hardcodedProviders) }
    }

    fun loadCurrentConfig() {
        viewModelScope.launch {
            val selectedId = configRepo.getSelectedHysteriaId()
            if (selectedId.isBlank()) {
                _state.update { it.copy(selectedLocation = null) }
                return@launch
            }

            val savedHysteria = configRepo.loadHysteriaConfig(selectedId)
            val normalized = savedHysteria.normalized()

            val locationItem = LocationItem(selectedId, normalized.displayName(), normalized)

            _state.update {
                it.copy(
                    configData = normalized,
                    selectedTurnType = normalized.bypassProvider,
                    selectedLocation = locationItem
                )
            }
        }
    }

    suspend fun performPing(): Long? {
        return vpnManager.ping(_state.value.configData)
    }

    suspend fun performPingFor(config: HysteriaConfig): Long? {
        return vpnManager.ping(config)
    }

    suspend fun checkConnectionFor(config: HysteriaConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        if (_state.value.isVpnLoading) return

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected) {
                    vpnManager.stopVpn()
                } else {
                    val selectedId = configRepo.getSelectedHysteriaId()
                    if (selectedId.isNotBlank()) {
                        configRepo.saveHysteriaConfig(state.value.configData, selectedId)
                    }
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun onServerOptionSelected(id: Int) {
        val provider = _state.value.providers.find { it.id == id } ?: return
        val newType = HysteriaConfig.normalizeProvider(provider.code)

        viewModelScope.launch {
            val updatedConfig = _state.value.configData.copy(bypassProvider = newType).normalized()
            val selectedId = configRepo.getSelectedHysteriaId()
            if (selectedId.isNotBlank()) {
                configRepo.saveHysteriaConfig(updatedConfig, selectedId)
            }

            _state.update {
                it.copy(
                    selectedTurnType = newType,
                    configData = updatedConfig,
                    selectedLocation = it.selectedLocation?.copy(config = updatedConfig)
                )
            }
        }
    }

    fun onServerChanged(value: String) = updateHysteriaConfig { it.copy(id = value) }
    fun onPasswordChanged(value: String) = updateHysteriaConfig { it.copy(key = value) }
    fun onSniChanged(value: String) = Unit

    fun onTurnEnabledChanged(value: Boolean) = updateTurnConfig { it.copy(enabled = value) }
    fun onTurnPeerChanged(value: String) = updateTurnConfig { it.copy(peer = value) }
    fun onTurnLinkChanged(value: String) = updateTurnConfig { it.copy(link = value) }
    fun onTurnUserChanged(value: String) = updateTurnConfig { it.copy(user = value) }
    fun onTurnPassChanged(value: String) = updateTurnConfig { it.copy(pass = value) }
    fun onTurnUdpChanged(value: Boolean) = updateTurnConfig { it.copy(udp = value) }
    fun onTurnThreadsChanged(threads: String) {
        val n = threads.toIntOrNull() ?: 8
        updateTurnConfig { it.copy(threads = n) }
    }

    private fun updateHysteriaConfig(block: (HysteriaConfig) -> HysteriaConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }

    private fun updateTurnConfig(block: (TurnConfig) -> TurnConfig) {
        _state.update { it.copy(turnData = block(it.turnData)) }
    }

    fun onConfigConfirmed() {
        if (isUserConfigValid()) {
            viewModelScope.launch {
                val selectedId = configRepo.getSelectedHysteriaId()
                if (selectedId.isNotBlank()) {
                    configRepo.saveHysteriaConfig(_state.value.configData, selectedId)
                }
            }
        } else {
            _state.update { it.copy(shouldShowConfigInvalidReminder = true) }
        }
    }

    fun onConfigInvalidReminderDismissed() {
        _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
    }

    fun onCopyFullConfigClicked() {
        val fullData = state.value.configData.toJsonConfig()
        configImporter.copyToClipboard(fullData)
    }

    fun onPasteFromClipboard() {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text)
        }
    }

    fun onFileSelected(fileSource: Any) {
        viewModelScope.launch {
            configImporter.readTextFromSource(fileSource)?.let { text ->
                onImportFullConfig(text)
            }
        }
    }

    private fun isUserConfigValid(): Boolean {
        return _state.value.configData.isComplete()
    }

    fun onRawConfigImported(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            configRepo.saveRawConfig(rawText)
            _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
        }
    }

    fun onImportFullConfig(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            try {
                configRepo.saveRawConfig(rawText)
                loadCurrentConfig()
            } catch (e: Exception) {
                // add error to state
            }
        }
    }
}

data class UiState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: HysteriaConfig,
    val turnData: TurnConfig,
    val selectedTurnType: String,
    val shouldShowConfigInvalidReminder: Boolean,
    val providers: List<ProviderConfig> = emptyList()
)
