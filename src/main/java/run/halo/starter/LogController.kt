package run.halo.starter

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rest/log")
class LogController {
    @RequestMapping("/list")
    fun getLog(): List<String> = LogCollector.getLogs()
}