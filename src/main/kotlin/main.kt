package info.remzi.currencyquiz

fun main(args: Array<String>) {
    loadLocalEnvironment()
    io.ktor.server.netty.EngineMain.main(args)
}
