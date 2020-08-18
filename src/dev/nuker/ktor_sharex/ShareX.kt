package dev.nuker.ktor_sharex

import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.http.defaultForFile
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.util.AttributeKey
import io.ktor.util.cio.readChannel
import io.ktor.util.combineSafe
import io.ktor.util.toMap
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.*
import kotlin.random.Random

object ShareX: ApplicationFeature<ApplicationCallPipeline, ShareX, ShareX> {
    var folder = File("sharex")
    var users = mutableListOf<ShareXUser>()
    var filenameGen: () -> String = RandomNameGen

    override val key: AttributeKey<ShareX> = AttributeKey("ShareX")

    override fun install(pipeline: ApplicationCallPipeline, configure: ShareX.() -> Unit): ShareX {
        return ShareX.also(configure).also {
            File(folder, "links").also { if (!it.exists()) it.mkdirs() }
            File(folder, "uploads").also { if (!it.exists()) it.mkdirs() }
            File(folder, "delete").also { if (!it.exists()) it.mkdirs() }
        }
    }

    object RandomNameGen: () -> String {
        override fun invoke(): String = Random.nextLong().toString(36).replace("-", "")
    }

    class ShareXUser(val username: String, val password: String)

    fun host(route: Route, folder: String = "", uploads: Boolean = true, links: Boolean = true, callback: ((ApplicationCall, String, String) -> Unit)? = null) = route.apply {
        static(folder) {
            get("{static-content-path-parameter...}") {
                val path = call.parameters.getAll("static-content-path-parameter")
                println(path)
                //val relativePath = call.parameters.getAll("static-content-path-parameter")?.joinToString(File.separator) ?: return@get
                if (path != null && path.size in 1..2) {
                    when {
                        path.size == 1 && File(path[0]).extension == "" -> {
                            val file = File(ShareX.folder, "links").combineSafe(path[0].substringBeforeLast("."))
                            if (file.isFile) {
                                callback?.invoke(call, path[0], if (path.size == 2) path[1] else path[0])
                                val link = String(file.inputStream().buffered().readBytes(), Charsets.UTF_8)
                                call.respondRedirect(link, false)
                            }
                        }
                        else -> {
                            val file = File(ShareX.folder, "uploads").combineSafe(path[0].substringBeforeLast("."))
                            val interpFile = File(if (path.size == 2) path[1] else path[0])
                            if (file.isFile) {
                                callback?.invoke(call, path[0], if (path.size == 2) path[1] else path[0])
                                call.respond(object: OutgoingContent.ReadChannelContent() { // this way it gets the right content type

                                    override val contentType = ContentType.defaultForFile(interpFile)

                                    override val contentLength: Long get() = file.length()

                                    override fun readFrom(): ByteReadChannel = file.readChannel()

                                    override fun readFrom(range: LongRange): ByteReadChannel = file.readChannel(range.start, range.endInclusive)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    fun upload(route: Route, folder: String = "", uploads: Boolean = true, links: Boolean = true, callback: ((ApplicationCall, String) -> Unit)? = null) = route.apply {
        post(folder) {
            if (!this.call.parameters.contains("delete")) {
                val multipart = this.call.receiveMultipart()
                var username: String? = null
                var password: String? = null
                var input: String? = null
                var file: File? = null
                var stream: (() -> InputStream)? = null
                multipart.forEachPart {
                    when (it) {
                        is PartData.FormItem -> {
                            when (it.name) {
                                "username" -> username = it.value
                                "password" -> password = it.value
                                "input" -> input = it.value
                            }
                        }
                        is PartData.FileItem -> {
                            file = File(it.originalFileName!!)
                            stream = it.streamProvider
                        }
                    }
                }
                println("$username -> $password")
                if (users.any { it.username == username && it.password == password }) {
                    when {
                        file == null && input != null -> { // url upload
                            val name = filenameGen()
                            val file = File(ShareX.folder, "links/$name")
                            file.outputStream().use {
                                it.write(input!!.toByteArray(Charsets.UTF_8))
                            }
                            val delete = makeDeleteKey(name)
                            call.respondText("""{"view":"$name","delete":"$name?delete=$delete"}""")
                        }
                        file != null -> {
                            val name = filenameGen()
                            val file2 = File(ShareX.folder, "uploads/$name")
                            stream!!().use { input ->
                                file2.outputStream().use {
                                    input.copyToSuspend(it)
                                }
                                val delete = makeDeleteKey(name)
                                call.respondText("""{"view":"$name/${file!!.name.replace("\"", "\\\"")}","delete":"${file!!.name.replace("\"", "\\\"")}?delete=$delete"}""")
                            }
                        }
                        else -> call.respond(HttpStatusCode.BadRequest)
                    }
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }
        static(folder) {
            get("{static-content-path-parameter...}") {
                val path = call.parameters.getAll("static-content-path-parameter")
                if (path != null && path.size == 1 && call.parameters.contains("delete")) {
                    println(call.parameters.toMap())
                    val key = call.parameters["delete"]
                    val file = File(ShareX.folder, "delete").combineSafe(path[0])
                    if (file.exists()) {
                        val realkey = String(file.inputStream().buffered().readBytes(), Charsets.UTF_8)
                        if (realkey == key) {
                            file.delete()
                            File(ShareX.folder, "links").combineSafe(path[0]).also { if (it.exists()) it.delete() }
                            File(ShareX.folder, "uploads").combineSafe(path[0]).also { if (it.exists()) it.delete() }
                            call.respondText("Deleted")
                        } else {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
        }
    }

    private fun makeDeleteKey(name: String): String {
        val file = File(ShareX.folder, "delete/$name")
        var key = ""
        for (i in 0 until 5) {
            key += UUID.randomUUID().toString().replace("-", "")
        }
        file.outputStream().use {
            it.write(key.toByteArray(Charsets.UTF_8))
        }
        return key
    }
}


/**
 * Skidded from cookie sharex
 * @author cookiedragon234 09/Mar/2020
 */
suspend fun InputStream.copyToSuspend(
    out: OutputStream,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    yieldSize: Int = 4 * 1024 * 1024,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): Long {
    return withContext(dispatcher) {
        val buffer = ByteArray(bufferSize)
        var bytesCopied = 0L
        var bytesAfterYield = 0L
        while (true) {
            val bytes = read(buffer).takeIf { it >= 0 } ?: break
            out.write(buffer, 0, bytes)
            if (bytesAfterYield >= yieldSize) {
                yield()
                bytesAfterYield %= yieldSize
            }
            bytesCopied += bytes
            bytesAfterYield += bytes
        }
        return@withContext bytesCopied
    }
}