package top.cxmeow.risingstones.feature.recruitment.ui.compose

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml

@Composable
internal fun RecruitmentFormattedText(html: String) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    var linkFailed by remember(html) { mutableStateOf(false) }
    val text = remember(html, uriHandler, linkColor) {
        AnnotatedString.fromHtml(html,
            linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor)),
            linkInteractionListener = { link ->
                val url = (link as? LinkAnnotation.Url)?.url
                linkFailed = !openRecruitmentContentLink(uriHandler, url)
            },
        )
    }
    Text(text)
    if (linkFailed) Text(stringResource(R.string.recruitment_link_unavailable),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal fun openRecruitmentContentLink(handler: UriHandler, url: String?): Boolean {
    val uri = url?.let(Uri::parse) ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
        !uri.userInfo.isNullOrEmpty() || (uri.port != -1 && uri.port != 443)) return false
    return runCatching { handler.openUri(url) }.isSuccess
}
