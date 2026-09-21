package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.cxmeow.risingstones.network.OkHttpRisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy
import top.cxmeow.risingstones.network.RisingStonesCosSigning

class NetworkApiBoundary(
    okHttpClient: OkHttpClient,
    json: Json,
) {
    val transport = OkHttpRisingStonesHttpClient(okHttpClient)
    fun acceptsOfficialResponse(code: Int?): Boolean = RisingStonesResponsePolicy.accepts(code)
    val validator = RisingStonesApiClient(
        httpClient = okHttpClient,
        json = json,
    )

    fun objectPutAuthorization(url: String, length: Int, id: String, key: String, start: Long, end: Long): String =
        RisingStonesCosSigning.putAuthorization(url, length, id, key, start, end)
}
