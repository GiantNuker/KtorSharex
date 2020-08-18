package dev.nuker.ktorsharex

import dev.nuker.ktorsharex.ShareX.uploadMP
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
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.random.Random

object ShareX : ApplicationFeature<ApplicationCallPipeline, ShareX, ShareX> {
    var folder = File("sharex")
    var users = mutableListOf<ShareXUser>()
    var filenameGen: () -> String = TimeNameGen
    var urlGetter: (String, String) -> String = { sub, name -> error("no custom url generator defined") }

    override val key: AttributeKey<ShareX> = AttributeKey("ShareX")

    override fun install(pipeline: ApplicationCallPipeline, configure: ShareX.() -> Unit): ShareX {
        return ShareX.also(configure)
    }

    object RandomNameGen : () -> String {
        override fun invoke(): String = Random.nextLong().toString(36).replace("-", "")
    }

    object TimeNameGen : () -> String {
        override fun invoke(): String = System.currentTimeMillis().toString(36)
    }

    class ShareXUser(val username: String, val password: String)

    fun host(route: Route, path: String = "", subfolder: String? = null, uploads: Boolean = true, links: Boolean = true, callback: ((ApplicationCall, String, String) -> Unit)? = null) = route.apply {
        val subfolder = if (subfolder == null) "" else "$subfolder/"
        static(path) {
            get("{static-content-path-parameter...}") {
                val path = call.parameters.getAll("static-content-path-parameter")
                if (path != null && path.size in 1..2) {
                    when {
                        path.size == 1 && File(path[0]).extension == "" && links -> {
                            val file = File(ShareX.folder, "${subfolder}links").combineSafe(path[0].substringBeforeLast("."))
                            if (file.isFile) {
                                callback?.invoke(call, path[0], if (path.size == 2) path[1] else path[0])
                                val stream = file.inputStream().buffered()
                                val link = String(stream.readBytes(), Charsets.UTF_8)
                                stream.close()
                                call.respondRedirect(link, false)
                            }
                        }
                        uploads -> {
                            val file = File(ShareX.folder, "${subfolder}uploads").combineSafe(path[0].substringBeforeLast("."))
                            val interpFile = File(if (path.size == 2) path[1] else path[0])
                            if (file.isFile) {
                                callback?.invoke(call, path[0], if (path.size == 2) path[1] else path[0])
                                call.respond(object : OutgoingContent.ReadChannelContent() { // this way it gets the right content type

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

    suspend fun PipelineContext<Unit, ApplicationCall>.uploadMP(route: Route, multipart: List<PartData>, path: String = "", subfolder: String? = null, uploads: Boolean = true, links: Boolean = true) {
        val subfolder = if (subfolder == null) "" else "$subfolder/"
        var username: String? = null
        var password: String? = null
        var input: String? = null
        var file: File? = null
        var stream: (() -> InputStream)? = null
        multipart.forEach {
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
        if (users.any { it.username == username && it.password == password }) {
            when {
                file == null && input != null && links -> { // url upload
                    val name = filenameGen()
                    val file = File(ShareX.folder, "${subfolder}links/$name")
                    file.parentFile.mkdirs()
                    file.outputStream().use {
                        it.write(input!!.toByteArray(Charsets.UTF_8))
                        it.close()
                    }
                    val delete = makeDeleteKey(subfolder, name)
                    call.respondText("""{"url":"${urlGetter(subfolder, name)}","raw":"$name","sub":"$subfolder","view":"$name","delete":"$name?delete=$delete"}""")
                }
                file != null && uploads -> {
                    val name = filenameGen()
                    val file2 = File(ShareX.folder, "${subfolder}uploads/$name")
                    file2.parentFile.mkdirs()
                    stream!!().use { input ->
                        file2.outputStream().use {
                            input.copyToSuspend(it)
                            it.close()
                        }
                        val delete = makeDeleteKey(subfolder, name)
                        call.respondText("""{"url":"${urlGetter(subfolder, name)}/${file!!.name.replace("\"", "\\\"")}","raw":"$name","sub":"$subfolder","view":"$name/${file!!.name.replace("\"", "\\\"")}","delete":"$name?delete=$delete"}""")
                    }
                }
                else -> call.respond(HttpStatusCode.BadRequest)
            }
        } else {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }

    private fun Route.deleteUpload(path: String = "", subfolder: (ApplicationCall) -> String?, uploads: Boolean = true, links: Boolean = true) {
        static(path) {
            get("{static-content-path-parameter...}") {
                val path = call.parameters.getAll("static-content-path-parameter")
                if (path != null && path.size == 1 && call.parameters.contains("delete")) {
                    val key = call.parameters["delete"]
                    var subfolder = subfolder(call)
                    if (subfolder == null) subfolder = ""
                    val file = File(ShareX.folder, "${subfolder}delete").combineSafe(path[0])
                    if (file.exists()) {
                        val stream = file.inputStream().buffered()
                        val realkey = String(stream.readBytes(), Charsets.UTF_8)
                        stream.close()
                        if (realkey == key) {
                            println(file.exists())
                            file.delete()
                            println(file.exists())
                            File(ShareX.folder, "${subfolder}links").combineSafe(path[0]).also { if (it.exists()) it.delete() }
                            File(ShareX.folder, "${subfolder}uploads").combineSafe(path[0]).also { if (it.exists()) it.delete() }
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

    fun upload(route: Route, path: String = "", subfolder: String? = null, uploads: Boolean = true, links: Boolean = true) = route.apply {
        val subfolder = if (subfolder == null) "" else "$subfolder/"
        post(path) {
            if (!this.call.parameters.contains("delete")) {
                val multipart = this.call.receiveMultipart()
                uploadMP(route, multipart.readAllParts(), path, subfolder, uploads, links)
            }
        }
        deleteUpload(path, { subfolder }, uploads, links)
    }

    fun uploadPickSub(route: Route, path: String = "", subfolders: Map<String, String?>, uploads: Boolean = true, links: Boolean = true) = route.apply {
        post(path) {
            if (!this.call.parameters.contains("delete")) {
                val multipart = this.call.receiveMultipart()
                val parts = multipart.readAllParts()
                val subfolderKey = (parts.filter { it.name == "endpoint" && it is PartData.FormItem }.firstOrNull() as PartData.FormItem?)?.value
                val subfolder = subfolders[subfolderKey]
                uploadMP(route, parts, path, subfolder, uploads, links)
            }
        }
        deleteUpload(path, { if (it.parameters.contains("sub")) it.parameters["sub"] else null }, uploads, links)
    }

    private fun makeDeleteKey(subfolder: String, name: String): String {
        val file = File(ShareX.folder, "${subfolder}delete/$name")
        file.parentFile.mkdirs()
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