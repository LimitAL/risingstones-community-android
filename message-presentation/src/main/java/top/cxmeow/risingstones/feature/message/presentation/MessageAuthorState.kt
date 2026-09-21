package top.cxmeow.risingstones.feature.message.presentation

import top.cxmeow.risingstones.feature.message.domain.MessageAuthorTarget

data class MessageAuthorState(val authorsByKey: Map<String, MessageAuthorTarget> = emptyMap())
