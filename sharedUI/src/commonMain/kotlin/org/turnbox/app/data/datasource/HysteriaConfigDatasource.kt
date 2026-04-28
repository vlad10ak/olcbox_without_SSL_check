package org.turnbox.app.data.datasource

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.model.TurnConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository

interface HysteriaConfigDataSource {
    suspend fun saveHysteriaConfig(config: HysteriaConfig, id: String = "default")
    suspend fun loadHysteriaConfig(id: String = "default"): HysteriaConfig
    suspend fun saveTurnConfig(config: TurnConfig, type: String = "custom")
    suspend fun loadTurnConfig(type: String = "custom"): TurnConfig
    suspend fun saveRawConfig(text: String)
    suspend fun getSelectedTurnType(): String
    suspend fun setSelectedTurnType(type: String)
    suspend fun getSelectedHysteriaId(): String
    suspend fun setSelectedHysteriaId(id: String)
    suspend fun getAllHysteriaConfigs(): List<Pair<String, HysteriaConfig>>
    suspend fun deleteHysteriaConfig(id: String)
}

@Serializable
private data class ImportWrapper(
    val version: Int = 1,
    val hysteria: HysteriaSection? = null,
    val turn: TurnSection? = null,
    val location: LocationSection? = null,
    val name: String = "",
    val id: String = "",
    val key: String = "",
    val provider: String = "",
    val bypass_provider: String = "",
    val bypassProvider: String = ""
)

@Serializable
private data class HysteriaSection(
    val server: String = "",
    val name: String = "",
    val password: String = "",
    val id: String = "",
    val key: String = "",
    val provider: String = "",
    val bypass_provider: String = "",
    val bypassProvider: String = ""
)

@Serializable
private data class LocationSection(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    val provider: String = "",
    val bypass_provider: String = "",
    val bypassProvider: String = ""
)

@Serializable
private data class TurnSection(
    val type: String = "custom",
    val enabled: Boolean = false,
    val peer: String = "",
    val link: String = "",
    val user: String = "",
    val pass: String = "",
    val threads: Int = 8,
    val udp: Boolean = true,
    val noDtls: Boolean = false,
    val listen: String = "127.0.0.1:9000"
)

class HysteriaConfigRepositoryImpl(
    private val dataSource: HysteriaConfigDataSource
) : HysteriaConfigRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun saveHysteriaConfig(config: HysteriaConfig, id: String) {
        dataSource.saveHysteriaConfig(config, id)
    }

    override suspend fun loadHysteriaConfig(id: String): HysteriaConfig {
        return dataSource.loadHysteriaConfig(id)
    }

    override suspend fun saveTurnConfig(config: TurnConfig, type: String) {
        dataSource.saveTurnConfig(config, type)
    }

    override suspend fun loadTurnConfig(type: String): TurnConfig {
        return dataSource.loadTurnConfig(type)
    }

    override suspend fun saveRawConfig(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val wrapper = json.decodeFromString<ImportWrapper>(trimmed)

                val config = wrapper.toHysteriaConfig()
                if (config.isComplete()) {
                    val newId = "imported_${config.storageSlug()}"
                    dataSource.saveHysteriaConfig(config, newId)
                    dataSource.setSelectedHysteriaId(newId)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        dataSource.saveRawConfig(text)
    }

    override suspend fun getSelectedTurnType(): String {
        return dataSource.getSelectedTurnType()
    }

    override suspend fun setSelectedTurnType(type: String) {
        dataSource.setSelectedTurnType(type)
    }

    override suspend fun getSelectedHysteriaId(): String {
        return dataSource.getSelectedHysteriaId()
    }

    override suspend fun setSelectedHysteriaId(id: String) {
        dataSource.setSelectedHysteriaId(id)
    }

    override suspend fun getAllHysteriaConfigs(): List<Pair<String, HysteriaConfig>> {
        return dataSource.getAllHysteriaConfigs()
    }

    override suspend fun deleteHysteriaConfig(id: String) {
        dataSource.deleteHysteriaConfig(id)
    }

    private fun ImportWrapper.toHysteriaConfig(): HysteriaConfig {
        val section = location
        val legacy = hysteria
        val provider = firstNotBlank(
            section?.bypass_provider,
            section?.bypassProvider,
            section?.provider,
            legacy?.bypass_provider,
            legacy?.bypassProvider,
            legacy?.provider,
            bypass_provider,
            bypassProvider,
            this.provider,
            turn?.type
        )

        return HysteriaConfig(
            name = firstNotBlank(section?.name, legacy?.name, name),
            id = firstNotBlank(section?.id, legacy?.id, legacy?.server, id),
            key = firstNotBlank(section?.key, legacy?.key, legacy?.password, key),
            bypassProvider = provider
        ).normalized()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun HysteriaConfig.storageSlug(): String {
        val source = displayName().ifBlank { id }.ifBlank { "location" }
        return source
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "location" }
    }
}
