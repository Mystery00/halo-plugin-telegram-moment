package run.halo.starter.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.types.update.ChannelPostUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object Bot {
    private val log = LoggerFactory.getLogger(Bot::class.java)
    private val scope = CoroutineScope(Executors.newFixedThreadPool(5).asCoroutineDispatcher())
    private var bot: TelegramBot? = null

    fun initBot(
        token: String,
        apiUrl: String,
    ) {
        bot = telegramBot(token = token, apiUrl = apiUrl)
        scope.launch {
            bot?.buildBehaviourWithLongPolling {
                log.info("Bot started")
                println("Bot started")
                val me = getMe()
                log.info("Authorized on Bot: {}", me.username)
                println("Authorized on Bot: ${me.username}")
                channelPostsFlow.onEach {
                    handleChannel(it)
                }.launchIn(scope)
            }
        }
    }

    private suspend fun handleChannel(post: ChannelPostUpdate) {
        log.info("receive channel post from: {}", post.data.chat.id.chatId)
        println("receive channel post from: ${post.data.chat.id.chatId}")
    }
}