package top.cxmeow.risingstones.integration.mavenconsumer

import android.content.Context
import android.net.Uri
import top.cxmeow.risingstones.feature.dynamic.data.DynamicPublishingImagePreparationException
import top.cxmeow.risingstones.feature.dynamic.data.normalizeDynamicPublishingImage
import top.cxmeow.risingstones.feature.dynamic.data.prepareDynamicPublishingImage
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput

/** 仅编译公开数据层图片入口；不打开图库、生成凭据或执行上传。 */
internal object DynamicImagePreparationApiBoundary {
    suspend fun prepare(context: Context, uri: Uri): DynamicImageUploadInput =
        prepareDynamicPublishingImage(context, uri)
    fun normalize(bytes: ByteArray): DynamicImageUploadInput = normalizeDynamicPublishingImage(bytes)
    fun category(error: DynamicPublishingImagePreparationException): Boolean = error.tooLarge
}
