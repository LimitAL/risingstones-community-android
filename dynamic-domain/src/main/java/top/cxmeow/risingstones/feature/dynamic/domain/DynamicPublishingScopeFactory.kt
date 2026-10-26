package top.cxmeow.risingstones.feature.dynamic.domain

/** 可选的发布作用域工厂。只有编辑器已接受图片时才请求图片用途；不授予持久或全局能力。 */
interface DynamicPublishingScopeFactory {
    /** includeImages=false 的纯文字尝试无需图片能力；身份变化后的旧作用域不可复用。 */
    suspend fun beginPublishingScope(includeImages:Boolean):DynamicActionScope
}
