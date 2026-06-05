package me.magnum.rcheevosapi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class RALeaderboardInfoDto(
    @SerialName("LeaderboardData")
    val leaderboardData: RALeaderboardDataDto,
)

@Serializable
internal data class RALeaderboardDataDto(
    @SerialName("LBFormat")
    val format: String,
    @SerialName("Entries")
    val entries: List<RALeaderboardEntryDto>,
    @SerialName("TotalEntries")
    @Serializable(with = FlexibleIntSerializer::class)
    val totalEntries: Int,
)

@Serializable
internal data class RALeaderboardEntryDto(
    @SerialName("User")
    val user: String,
    @SerialName("Rank")
    val rank: Int,
    @SerialName("Score")
    val score: Int,
    @SerialName("DateSubmitted")
    val dateSubmitted: Long,
    @SerialName("AvatarUrl")
    val avatarUrl: String? = null,
)

private object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        return if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement().jsonPrimitive
            primitive.content.toIntOrNull() ?: primitive.int
        } else {
            decoder.decodeInt()
        }
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}
