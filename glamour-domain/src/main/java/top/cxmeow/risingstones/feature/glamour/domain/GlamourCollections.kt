package top.cxmeow.risingstones.feature.glamour.domain

const val GlamourFolderNameMaximumLength = 20

/** Optional collection management extension; existing GlamourService implementations remain valid. */
interface GlamourCollectionService : GlamourService {
    suspend fun updateFavoriteFolder(id: Int, name: String, isPublic: Boolean)
    suspend fun favoriteInFolder(id: Int, folderId: Int)
}
