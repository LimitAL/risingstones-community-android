package top.cxmeow.risingstones.feature.guild.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

/** Validates the read-only current-guild relationship without probing any write endpoint. */
class RisingStonesGuildSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : RisingStonesSessionValidator {
    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer).let { validation ->
            validation.copy(capabilities = validation.capabilities - RisingStonesCapability.GuildRead)
        }
        return try {
            val basic = readGuildPayload(client, json, authorizer, BasicInfoPath) as? JsonObject
                ?: return base
            when (val ownGuild = parseOwnGuild(basic)) {
                OwnGuild.None -> base.withGuildRead()
                is OwnGuild.Joined -> {
                    val info = readGuildPayload(
                        client = client,
                        json = json,
                        authorizer = authorizer,
                        path = "api/home/guild/getGuildInfo",
                        query = listOf(q("guild_id", ownGuild.guildId.value)),
                    ) as? JsonObject ?: return base
                    if (guildInfo(info).id == ownGuild.guildId) base.withGuildRead() else base
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            base
        }
    }
}

private fun RisingStonesSessionValidation.withGuildRead(): RisingStonesSessionValidation =
    copy(capabilities = capabilities + RisingStonesCapability.GuildRead)
