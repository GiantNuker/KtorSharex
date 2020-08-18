package dev.nuker.dev.nuker.ktor_sharex

import java.util.*

class ShareXConfig(
    folder: String = "ShareX",
    mode: MetaSave = MetaSave.PREPEND,
    users: List<ShareXUser>
) {
    class ShareXUser(
        username: String,
        password: String,
        permissions: ShareXPerms = ShareXPerms()
    )
    class ShareXPerms(
        upload: Boolean = true,
        pwdUpload: Boolean = true,
        urlShorten: Boolean = true,
        pwdShorten: Boolean = true
    )
    enum class MetaSave {
        JSON, PREPEND
    }
}

