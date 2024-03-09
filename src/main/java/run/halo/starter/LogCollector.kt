package run.halo.starter

import java.util.*

fun log(message: Any) {
    LogCollector.log(message.toString())
}

object LogCollector {
    private val queue = LinkedList<String>()

    fun log(message: String) {
        if (queue.size >= 1000) {
            queue.poll()
        }
        println(message)
        queue.offer(message)
    }

    fun getLogs(): List<String> {
        return queue.toList()
    }
}