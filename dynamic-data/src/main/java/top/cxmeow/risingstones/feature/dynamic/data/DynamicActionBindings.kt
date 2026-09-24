package top.cxmeow.risingstones.feature.dynamic.data

import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScope
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicUploadedImage

internal class OfficialDynamicActionScope(
    internal val delegate: RisingStonesCapabilityScope,
    internal val sessionProvider: RisingStonesSessionProvider,
) : DynamicActionScope {
    internal val observedCommentAuthors: MutableMap<Int, String?> = Collections.synchronizedMap(mutableMapOf())
    internal var currentIdentity: DynamicIdentityState = DynamicIdentityState.Unread
    internal val identityMutex = Mutex()

    override suspend fun isCurrent(): Boolean = delegate.isCurrent()
    override fun close() = delegate.close()
}

internal sealed interface DynamicIdentityState {
    data object Unread : DynamicIdentityState
    data object Unknown : DynamicIdentityState
    data class Known(val uuid: String) : DynamicIdentityState
}

internal interface BoundDynamicUploadedImage : DynamicUploadedImage {
    val actionScope: OfficialDynamicActionScope
    val url: String
    val purpose: DynamicImagePurpose
}

internal enum class DynamicImagePurpose { Comment, Publishing }
