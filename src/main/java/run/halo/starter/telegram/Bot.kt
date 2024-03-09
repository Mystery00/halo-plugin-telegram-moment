package run.halo.starter.telegram

import com.fasterxml.jackson.databind.JsonNode
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.types.update.ChannelPostUpdate
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import run.halo.app.plugin.SettingFetcher
import run.halo.starter.log
import java.util.concurrent.Executors

@Component
class Bot(
    private val settingFetcher: SettingFetcher
) {
    private val apiUrl: String = conf("telegram.apiUrl") ?: "https://api.telegram.org"
    private val token: String = conf("telegram.botToken") ?: ""

    companion object {
        private val scope = CoroutineScope(Executors.newFixedThreadPool(5).asCoroutineDispatcher())
    }

    @PostConstruct
    fun start() {
        if (token.isBlank()) {
            log("Bot token is blank")
            return
        }
        val bot = telegramBot(token = token, apiUrl = apiUrl)
        scope.launch {
            bot.buildBehaviourWithLongPolling {
                log("Bot started")
                val me = getMe()
                log("Authorized on Bot: ${me.username}")
                if (boolConf("channel.enabled")) {
                    channelPostsFlow.onEach {
                        handleChannel(it)
                    }.launchIn(scope)
                } else {
                    log("channel.enabled = false")
                }
            }
        }
    }

    private suspend fun handleChannel(post: ChannelPostUpdate) {
        val sourceChannelId = post.data.chat.id.chatId
        log("receive channel post from: $sourceChannelId")
        val enabledIdList = settingFetcher.get("channel").get("idList")
            .elements()
            .asSequence()
            .map { it.get("id").asText() }
            .filter { it.isNotBlank() }
            .map { it.toLong() }
            .toList()
        log(enabledIdList)
        if (!enabledIdList.contains(sourceChannelId)) {
            log("skip channel post from: $sourceChannelId")
        }
    }

    private fun conf(path: String): String? {
        var node: JsonNode?
        val split = path.split(".")
        node = settingFetcher.get(split[0])
        for (i in 1 until split.size) {
            if (node == null) return null
            node = node.get(split[i])
        }
        return node?.asText()
    }

    private fun boolConf(path: String, defaultValue: Boolean = false): Boolean =
        conf(path)?.toBoolean() ?: defaultValue
}