package top.cxmeow.risingstones.feature.personaldata.domain

/** Public static resources only. This optional extension never reads character data or a session. */
interface PersonalDataShareResourceService {
    suspend fun fetchShareCatalogs(): PersonalDataShareCatalogs
    fun sharePageUrl(kind: PersonalDataShareKind): String
    fun shareImageUrl(image: PersonalDataShareImage): String?
}

sealed interface PersonalDataShareImage {
    data class Cover(val kind: PersonalDataShareKind) : PersonalDataShareImage
    data class UltimateCover(val territoryType: Int) : PersonalDataShareImage
    data class UltimateMedal(val territoryType: Int) : PersonalDataShareImage
    data class RaidCover(val imageId: Int) : PersonalDataShareImage
    data class ItemIcon(val iconId: Int) : PersonalDataShareImage
    data class AchievementIcon(val iconId: Int) : PersonalDataShareImage
    data class PhantomJobIcon(val iconId: Int) : PersonalDataShareImage
    data class JobIcon(val jobName: String) : PersonalDataShareImage
    data object FrontlineBadge : PersonalDataShareImage
    data object Logo : PersonalDataShareImage
    data object GameLogo : PersonalDataShareImage
}

/** Renderers consume an immutable snapshot; they must never issue business API requests. */
fun interface PersonalDataShareRenderer {
    suspend fun render(document: PersonalDataShareDocument): PersonalDataShareArtifact
}

/** Encoded image in memory. No private payload appears in toString or an implicit log. */
class PersonalDataShareArtifact(
    png: ByteArray,
    val width: Int,
    val height: Int,
    val accessibleText: String,
    val missingImageCount: Int = 0,
) {
    private val encoded = png.copyOf()
    fun pngBytes(): ByteArray = encoded.copyOf()
}
