package org.turnbox.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConfig(
    val id: Int,
    val code: String,
    val name: String
)