package info.remzi.currencyquiz

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.minutes

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("login")) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { it.request.local.remoteHost }
        }
        register(RateLimitName("answers")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { it.request.local.remoteHost }
        }
    }
}
