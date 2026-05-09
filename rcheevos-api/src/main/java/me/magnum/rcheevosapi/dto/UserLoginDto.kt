package me.magnum.rcheevosapi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UserLoginDto(
    @SerialName("User")
    val user: String,
    @SerialName("Token")
    val token: String,
    @SerialName("Score")
    val score: Long,
    @SerialName("SoftcoreScore")
    val softcoreScore: Long,
)
