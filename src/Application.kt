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
        urlGetter = { sub, name -> "localhost/${
        when (sub) {
            "e1/" -> "endpoint1/"
            else -> "endpoint2/"
        }
        }$name" }
    }

    routing {
        ShareX.uploadPickSub(this, "upload", mapOf("endpoint 1" to "e1", "endpoint 2" to "e2"))
        ShareX.host(this, "endpoint1", "e1")
        ShareX.host(this, "endpoint2", "e2")
    }
}

data class IndexData(val items: List<Int>)

