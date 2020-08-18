package dev.nuker

import dev.nuker.ktorsharex.ShareX
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.routing.routing

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
        ShareX.upload(this, "upload2", "2")
        ShareX.host(this, "host2", "2")
    }
}

data class IndexData(val items: List<Int>)

