package org.turnbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class HysteriaConfig(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER
) {

    fun normalized(): HysteriaConfig = copy(
        name = name.trim(),
        id = id.trim(),
        key = key.trim(),
        bypassProvider = normalizeProvider(bypassProvider)
    )

    fun isComplete(): Boolean = id.isNotBlank() && key.isNotBlank()

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    fun getFullConfig(): String {
        return toJsonConfig()
    }

    /**
     * Генерирует структурированный JSON конфиг для обмена (Copy/Paste)
     */
    fun toJsonConfig(): String {
        val json = Json { prettyPrint = true }
        val config = normalized()
        val root = buildJsonObject {
            put("version", 2)
            put("name", config.name)
            put("id", config.id)
            put("key", config.key)
            put("bypass_provider", config.bypassProvider)
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wb_stream"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        val supportedBypassProviders = listOf(
            PROVIDER_JAZZ,
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM
        )

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_JAZZ -> "Jazz"
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                else -> "WB Stream"
            }
        }
    }
}
