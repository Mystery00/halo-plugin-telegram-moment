package run.halo.starter

import org.springframework.stereotype.Component
import run.halo.app.plugin.BasePlugin
import run.halo.app.plugin.PluginContext
import run.halo.starter.telegram.Bot

@Component
class StarterPlugin(
    private val pluginContext: PluginContext
) : BasePlugin(pluginContext) {
    override fun start() {
        Bot.initBot("", "https://api.telegram.org")
        println("插件启动成功！")
    }

    override fun stop() {
        println("插件停止！")
    }
}