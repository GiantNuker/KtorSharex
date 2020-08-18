package dev.nuker

import dev.nuker.dev.nuker.ktor_sharex.ShareXConfig
import dev.nuker.ktor_sharex.ShareX
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    install(ShareX) {
        users.add(ShareX.ShareXUser("foo", "bar"))
    }

    routing {
        ShareX.upload(this, "upload")
        ShareX.host(this, "host")
    }
}

data class IndexData(val items: List<Int>)

