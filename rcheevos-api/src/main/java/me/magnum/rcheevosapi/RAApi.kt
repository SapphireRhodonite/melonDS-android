package me.magnum.rcheevosapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import me.magnum.melonds.common.suspendMapCatching
import me.magnum.melonds.common.suspendRunCatching
import me.magnum.rcheevosapi.dto.AwardAchievementResponseDto
import me.magnum.rcheevosapi.dto.GameAchievementSetsDto
import me.magnum.rcheevosapi.dto.HashLibraryDto
import me.magnum.rcheevosapi.dto.RALeaderboardInfoDto
import me.magnum.rcheevosapi.dto.RASubmitLeaderboardEntryResponseDto
import me.magnum.rcheevosapi.dto.UserLoginDto
import me.magnum.rcheevosapi.dto.UserUnlocksDto
import me.magnum.rcheevosapi.dto.mapper.mapToModel
import me.magnum.rcheevosapi.exception.UnsuccessfulRequestException
import me.magnum.rcheevosapi.exception.UserNotAuthenticatedException
import me.magnum.rcheevosapi.exception.UserTokenExpiredException
import me.magnum.rcheevosapi.model.RAAwardAchievementResponse
import me.magnum.rcheevosapi.model.RAGame
import me.magnum.rcheevosapi.model.RAGameId
import me.magnum.rcheevosapi.model.RALeaderboardRanking
import me.magnum.rcheevosapi.model.RALeaderboardRankingEntry
import me.magnum.rcheevosapi.model.RASubmitLeaderboardEntryResponse
import me.magnum.rcheevosapi.model.RAUserAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass

class RAApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val userAuthStore: RAUserAuthStore,
    private val signatureProvider: RASignatureProvider,
) {

    companion object {
        private const val BASE_URL = "https://retroachievements.org/dorequest.php"

        private const val PARAMETER_USER = "u"
        private const val PARAMETER_PASSWORD = "p"
        private const val PARAMETER_TOKEN = "t"
        private const val PARAMETER_REQUEST = "r"
        private const val PARAMETER_GAME_ID = "g"
        private const val PARAMETER_GAME_HASH = "m"
        private const val PARAMETER_PING_GAME_HASH = "x"
        private const val PARAMETER_ACHIEVEMENT_ID = "a"
        private const val PARAMETER_IS_HARDMODE = "h"
        private const val PARAMETER_RICH_PRESENCE = "m"
        private const val PARAMETER_SIGNATURE = "v"
        private const val PARAMETER_LEADERBOARD_ID = "i"
        private const val PARAMETER_SCORE = "s"
        private const val PARAMETER_RCHEEVOS_VERSION = "l"
        private const val PARAMETER_OFFSET = "o"
        private const val PARAMETER_COUNT = "c"

        private const val VALUE_HARDMODE_DISABLED = "0"
        private const val VALUE_HARDMODE_ENABLED = "1"

        private const val REQUEST_LOGIN = "login2"
        private const val REQUEST_HASH_LIBRARY = "hashlibrary"
        private const val REQUEST_ACHIEVEMENT_SETS = "achievementsets"
        private const val REQUEST_USER_UNLOCKED_ACHIEVEMENTS = "unlocks"
        private const val REQUEST_START_SESSION = "startsession"
        private const val REQUEST_AWARD_ACHIEVEMENT = "awardachievement"
        private const val REQUEST_SUBMIT_LEADERBOARD_ENTRY = "submitlbentry"
        private const val REQUEST_LEADERBOARD_INFO = "lbinfo"
        private const val REQUEST_PING = "ping"

        private const val RCHEEVOS_VERSION = "12.3.0"
    }

    suspend fun login(username: String, password: String): Result<Unit> {
        userAuthStore.clearUserAuth()
        return get<UserLoginDto>(
            mapOf(
                PARAMETER_REQUEST to REQUEST_LOGIN,
                PARAMETER_USER to username,
                PARAMETER_PASSWORD to password,
            ),
            clearTokenOnUnauthorized = false,
        ).onSuccess {
            userAuthStore.storeUserAuth(RAUserAuth.Authenticated(it.user, it.token))
        }.map { }
    }

    suspend fun getGameHashList(): Result<Map<String, RAGameId>> {
        return get<HashLibraryDto>(
            mapOf(PARAMETER_REQUEST to REQUEST_HASH_LIBRARY)
        ).map { library ->
            library.md5List.mapValues {
                RAGameId(it.value)
            }
        }
    }

    suspend fun getUserUnlockedAchievements(gameId: RAGameId, forHardcoreMode: Boolean): Result<List<Long>> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        return get<UserUnlocksDto>(
            mapOf(
                PARAMETER_REQUEST to REQUEST_USER_UNLOCKED_ACHIEVEMENTS,
                PARAMETER_USER to userAuth.username,
                PARAMETER_TOKEN to userAuth.token,
                PARAMETER_GAME_ID to gameId.id.toString(),
                PARAMETER_IS_HARDMODE to if (forHardcoreMode) VALUE_HARDMODE_ENABLED else VALUE_HARDMODE_DISABLED,
            )
        ).map {
            it.userUnlocks
        }
    }

    suspend fun getGameAchievementSets(gameHash: String): Result<RAGame> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        return get<GameAchievementSetsDto>(
            mapOf(
                PARAMETER_REQUEST to REQUEST_ACHIEVEMENT_SETS,
                PARAMETER_USER to userAuth.username,
                PARAMETER_TOKEN to userAuth.token,
                PARAMETER_GAME_HASH to gameHash,
            )
        ).suspendMapCatching {
            it.mapToModel()
        }
    }

    suspend fun startSession(gameId: RAGameId, gameHash: String, forHardcoreMode: Boolean): Result<Unit> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        return post(
            mapOf(
                PARAMETER_REQUEST to REQUEST_START_SESSION,
                PARAMETER_USER to userAuth.username,
                PARAMETER_TOKEN to userAuth.token,
                PARAMETER_GAME_ID to gameId.id.toString(),
                PARAMETER_GAME_HASH to gameHash,
                PARAMETER_IS_HARDMODE to if (forHardcoreMode) VALUE_HARDMODE_ENABLED else VALUE_HARDMODE_DISABLED,
                PARAMETER_RCHEEVOS_VERSION to RCHEEVOS_VERSION,
            )
        )
    }

    suspend fun awardAchievement(
        achievementId: Long,
        forHardcoreMode: Boolean,
        gameHash: String? = null,
    ): Result<RAAwardAchievementResponse> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        val signature = signatureProvider.provideAchievementSignature(achievementId, userAuth, forHardcoreMode)

        val parameters = mutableMapOf(
            PARAMETER_REQUEST to REQUEST_AWARD_ACHIEVEMENT,
            PARAMETER_USER to userAuth.username,
            PARAMETER_TOKEN to userAuth.token,
            PARAMETER_ACHIEVEMENT_ID to achievementId.toString(),
            PARAMETER_IS_HARDMODE to if (forHardcoreMode) VALUE_HARDMODE_ENABLED else VALUE_HARDMODE_DISABLED,
            PARAMETER_SIGNATURE to signature,
        )
        if (!gameHash.isNullOrBlank()) {
            parameters[PARAMETER_GAME_HASH] = gameHash
        }

        return get<AwardAchievementResponseDto>(
            parameters,
            errorHandler = {
                // Ignore errors if the achievement has already been awarded to the user
                if (it?.startsWith("User already has") != true) {
                    throw UnsuccessfulRequestException(it ?: "Unknown reason")
                }
            }
        ).map {
            RAAwardAchievementResponse(
                achievementAwarded = it.success,
                remainingAchievements = it.achievementsRemaining,
            )
        }
    }

    suspend fun submitLeaderboardEntry(
        leaderboardId: Long,
        value: Int,
        gameHash: String? = null,
    ): Result<RASubmitLeaderboardEntryResponse> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        val signature = signatureProvider.provideLeaderboardSignature(leaderboardId, value, userAuth)

        val parameters = mutableMapOf(
            PARAMETER_REQUEST to REQUEST_SUBMIT_LEADERBOARD_ENTRY,
            PARAMETER_USER to userAuth.username,
            PARAMETER_TOKEN to userAuth.token,
            PARAMETER_LEADERBOARD_ID to leaderboardId.toString(),
            PARAMETER_SCORE to value.toString(),
            PARAMETER_SIGNATURE to signature,
        )
        if (!gameHash.isNullOrBlank()) {
            parameters[PARAMETER_GAME_HASH] = gameHash
        }

        return get<RASubmitLeaderboardEntryResponseDto>(
            parameters,
        ).map {
            RASubmitLeaderboardEntryResponse(
                rank = it.response.rankInfo.rank,
                numEntries = it.response.rankInfo.numEntries,
            )
        }
    }

    suspend fun getLeaderboardRanking(
        leaderboardId: Long,
        firstEntry: Int = 1,
        count: Int = 25,
    ): Result<RALeaderboardRanking> {
        val offset = firstEntry.coerceAtLeast(1) - 1
        val entryCount = count.coerceIn(1, 100)

        return get<RALeaderboardInfoDto>(
            mapOf(
                PARAMETER_REQUEST to REQUEST_LEADERBOARD_INFO,
                PARAMETER_LEADERBOARD_ID to leaderboardId.toString(),
                PARAMETER_OFFSET to offset.toString(),
                PARAMETER_COUNT to entryCount.toString(),
            )
        ).suspendMapCatching { info ->
            val data = info.leaderboardData
            RALeaderboardRanking(
                leaderboardId = leaderboardId,
                totalEntries = data.totalEntries,
                entries = data.entries.map { entry ->
                    RALeaderboardRankingEntry(
                        user = entry.user,
                        rank = entry.rank,
                        rawScore = entry.score,
                        formattedScore = formatLeaderboardScore(data.format, entry.score),
                        submittedAtEpochSeconds = entry.dateSubmitted,
                        avatarUrl = entry.avatarUrl?.takeIf { it.isNotBlank() }?.let { URI(it).toURL() },
                    )
                },
            )
        }
    }

    suspend fun sendPing(gameId: RAGameId, gameHash: String, forHardcoreMode: Boolean, richPresenceDescription: String?): Result<Unit> {
        val userAuth = userAuthStore.getUserAuth() as? RAUserAuth.Authenticated
            ?: return Result.failure(UserNotAuthenticatedException())

        val parameters = mutableMapOf(
            PARAMETER_REQUEST to REQUEST_PING,
            PARAMETER_USER to userAuth.username,
            PARAMETER_TOKEN to userAuth.token,
            PARAMETER_GAME_ID to gameId.id.toString(),
            PARAMETER_PING_GAME_HASH to gameHash,
            PARAMETER_IS_HARDMODE to if (forHardcoreMode) VALUE_HARDMODE_ENABLED else VALUE_HARDMODE_DISABLED,
        )

        if (richPresenceDescription != null) {
            parameters[PARAMETER_RICH_PRESENCE] = richPresenceDescription
        }

        return post(parameters)
    }

    private suspend inline fun <reified T : Any> get(
        parameters: Map<String, String>,
        noinline errorHandler: (String?) -> Unit = { throw UnsuccessfulRequestException(it ?: "Unknown reason") },
        clearTokenOnUnauthorized: Boolean = true,
    ): Result<T> {
        return get(T::class, parameters, errorHandler, clearTokenOnUnauthorized)
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun <T : Any> get(
        responseClass: KClass<T>,
        parameters: Map<String, String>,
        errorHandler: (String?) -> Unit = { throw UnsuccessfulRequestException(it ?: "Unknown reason") },
        clearTokenOnUnauthorized: Boolean = true,
    ): Result<T> = withContext(Dispatchers.IO) {
        val request = buildGetRequest(parameters)
        suspendRunCatching {
            executeRequest(request, clearTokenOnUnauthorized)
        }.suspendMapCatching { response ->
            response.use {
                parseResponse(responseClass, response, errorHandler)
            }
        }
    }

    private suspend inline fun <reified T : Any> post(
        parameters: Map<String, String>,
        noinline errorHandler: (String?) -> Unit = { throw UnsuccessfulRequestException(it ?: "Unknown reason") },
    ): Result<T> {
        return post(T::class, parameters, errorHandler)
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun <T : Any> post(
        responseClass: KClass<T>,
        parameters: Map<String, String>,
        errorHandler: (String?) -> Unit = { throw UnsuccessfulRequestException(it ?: "Unknown reason") },
    ): Result<T> = withContext(Dispatchers.IO) {
        val request = buildPostRequest(parameters)
        suspendRunCatching {
            executeRequest(request)
        }.suspendMapCatching { response ->
            response.use {
                parseResponse(responseClass, response, errorHandler)
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun <T : Any> parseResponse(
        responseClass: KClass<T>,
        response: Response,
        errorHandler: (String?) -> Unit,
    ): T {
        val body = response.body.string()
        if (!response.isSuccessful) {
            throw UnsuccessfulRequestException(buildHttpErrorMessage(response, body))
        }

        val responseJson = parseRaResponse(body)
        validateRaResponseSuccess(responseJson, errorHandler)

        return if (responseClass == Unit::class) {
            // Ignore response. Don't parse anything
            @Suppress("UNCHECKED_CAST")
            Unit as T
        } else {
            json.decodeFromJsonElement(responseClass.serializer(), responseJson)
        }
    }

    private fun buildHttpErrorMessage(response: Response, body: String): String {
        val statusMessage = response.message.ifBlank { "no status message" }
        val bodySummary = body
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .take(200)
            .ifBlank { "empty body" }
        return "HTTP ${response.code}: $statusMessage; body=$bodySummary"
    }

    private fun buildGetRequest(parameters: Map<String, String>): Request {
        val query = parameters.map {
            "${URLEncoder.encode(it.key, "utf-8")}=${URLEncoder.encode(it.value, "utf-8")}"
        }.joinToString(separator = "&")

        val url = "$BASE_URL?$query"

        return Request.Builder()
            .get()
            .url(url)
            .build()
    }

    private fun buildPostRequest(parameters: Map<String, String>): Request {
        val data = parameters.map {
            "${URLEncoder.encode(it.key, "utf-8")}=${URLEncoder.encode(it.value, "utf-8")}"
        }.joinToString(separator = "&")

        return Request.Builder()
            .post(data.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .url(BASE_URL)
            .build()
    }

    private suspend fun executeRequest(request: Request, clearTokenOnUnauthorized: Boolean = true): Response = suspendCancellableCoroutine { continuation ->
        val call = okHttpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401 && clearTokenOnUnauthorized) {
                    runBlocking {
                        userAuthStore.clearUserToken()
                    }
                    continuation.resumeWithException(UserTokenExpiredException())
                } else {
                    continuation.resume(response)
                }
            }
        })

        continuation.invokeOnCancellation {
            call.cancel()
        }
    }

    private fun parseRaResponse(body: String): JsonObject {
        if (body.isBlank()) {
            throw UnsuccessfulRequestException("RA response body is empty")
        }

        return try {
            Json.parseToJsonElement(body).jsonObject
        } catch (exception: SerializationException) {
            throw UnsuccessfulRequestException("RA response is not valid JSON: ${body.take(200)}")
        } catch (exception: IllegalArgumentException) {
            throw UnsuccessfulRequestException("RA response is not a JSON object: ${body.take(200)}")
        }
    }

    private fun validateRaResponseSuccess(responseJson: JsonObject, errorHandler: (String?) -> Unit) {
        val successElement = responseJson["Success"]
        if (successElement == null) {
            val explicitError = responseJson["Error"]?.jsonPrimitive?.contentOrNull
            val fallbackMessage = explicitError ?: "RA response missing field: Success"
            errorHandler.invoke(fallbackMessage)
            return
        }

        val isSuccess = parseRaBoolean(successElement)
        if (!isSuccess) {
            val reason = responseJson["Error"]?.jsonPrimitive?.contentOrNull
            // The error handler may choose to ignore the error
            errorHandler.invoke(reason ?: "Unknown reason")
        }
    }

    private fun parseRaBoolean(value: JsonElement): Boolean {
        val primitive = value.jsonPrimitive
        primitive.booleanOrNull?.let { return it }

        val content = primitive.contentOrNull?.trim()?.lowercase() ?: throw UnsuccessfulRequestException("RA response value is empty")
        if (content == "1" || content == "true" || content == "yes" || content == "on") {
            return true
        }
        if (content == "0" || content == "false" || content == "no" || content == "off") {
            return false
        }

        throw UnsuccessfulRequestException("RA response has invalid Success value: $content")
    }

    private fun formatLeaderboardScore(format: String, score: Int): String {
        return when (format.uppercase(Locale.ROOT)) {
            "SCORE", "POINTS", "OTHER" -> "%06d".format(Locale.US, score)
            "TIME" -> formatCentiseconds(score.toLong() * 10L / 6L)
            "MILLISECS" -> formatCentiseconds(score.toLong())
            "TIMESECS", "SECS" -> formatSeconds(score.toLong())
            "SECS_AS_MINS" -> formatMinutes(score.toLong() / 60L)
            "MINUTES" -> formatMinutes(score.toLong())
            "FLOAT1" -> "%,.1f".format(Locale.US, score.toDouble())
            "FLOAT2" -> "%,.2f".format(Locale.US, score.toDouble())
            "FLOAT3" -> "%,.3f".format(Locale.US, score.toDouble())
            "FLOAT4" -> "%,.4f".format(Locale.US, score.toDouble())
            "FLOAT5" -> "%,.5f".format(Locale.US, score.toDouble())
            "FLOAT6" -> "%,.6f".format(Locale.US, score.toDouble())
            "FIXED1" -> formatFixed(score, 10, 1)
            "FIXED2" -> formatFixed(score, 100, 2)
            "FIXED3" -> formatFixed(score, 1000, 3)
            "TENS" -> if (score == 0) "0" else formatNumber(score.toLong() * 10L)
            "HUNDREDS" -> if (score == 0) "0" else formatNumber(score.toLong() * 100L)
            "THOUSANDS" -> if (score == 0) "0" else formatNumber(score.toLong() * 1000L)
            "UNFORMATTED" -> score.toUInt().toString()
            "UNSIGNED" -> score.toUInt().toString()
            else -> formatNumber(score.toLong())
        }
    }

    private fun formatCentiseconds(rawCentiseconds: Long): String {
        val centiseconds = rawCentiseconds.coerceAtLeast(0L)
        val seconds = centiseconds / 100L
        val remainingCentiseconds = centiseconds % 100L
        return "${formatSeconds(seconds)}.%02d".format(Locale.US, remainingCentiseconds)
    }

    private fun formatSeconds(rawSeconds: Long): String {
        val totalSeconds = rawSeconds.coerceAtLeast(0L)
        val totalMinutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (totalMinutes < 60L) {
            return "%d:%02d".format(Locale.US, totalMinutes, seconds)
        }

        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return "%dh%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    private fun formatMinutes(rawMinutes: Long): String {
        val totalMinutes = rawMinutes.coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return "%dh%02d".format(Locale.US, hours, minutes)
    }

    private fun formatFixed(score: Int, factor: Int, decimals: Int): String {
        val whole = score / factor
        val remainder = kotlin.math.abs(score % factor)
        return "${formatNumber(whole.toLong())}.${remainder.toString().padStart(decimals, '0')}"
    }

    private fun formatNumber(value: Long): String {
        return NumberFormat.getIntegerInstance(Locale.US).format(value)
    }
}
