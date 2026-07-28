package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.cxmeow.risingstones.network.OkHttpRisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesApiClient

class NetworkApiBoundary(
    okHttpClient: OkHttpClient,
    json: Json,
) {
    val transport = OkHttpRisingStonesHttpClient(okHttpClient)
    val validator = RisingStonesApiClient(
        httpClient = okHttpClient,
        json = json,
    )
}
