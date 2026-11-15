package org.evsyukov.post.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.evsyukov.post.BuildConfig
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBar

object AboutTags {
    const val SCREEN = SettingsSectionTags.ABOUT
    const val ICON = "about-icon"
    const val VERSION = "about-version"
    const val SOURCE = "about-source"
    const val PRIVACY = "about-privacy"
    const val LICENSE = "about-license"

    const val SOURCE_URL = "https://github.com/juev/post"
    const val PRIVACY_URL = "https://github.com/juev/post/blob/main/docs/privacy.md"
    const val LICENSE_URL = "https://github.com/juev/post/blob/main/LICENSE"
}

/** Settings → About (screens.md): the icon, version name and code, source, privacy note, license. */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    val uri = LocalUriHandler.current
    Scaffold(
        modifier = Modifier.testTag(AboutTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings_about), onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // painterResource cannot draw an adaptive icon, so the two layers are stacked by hand.
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).testTag(AboutTags.ICON)) {
                    Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.about_icon),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, versionName, versionCode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(AboutTags.VERSION),
                )
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            LinkRow(Icons.Outlined.Code, stringResource(R.string.about_source), AboutTags.SOURCE_URL, AboutTags.SOURCE) { uri.openUri(AboutTags.SOURCE_URL) }
            LinkRow(Icons.Outlined.PrivacyTip, stringResource(R.string.about_privacy), AboutTags.PRIVACY_URL, AboutTags.PRIVACY) { uri.openUri(AboutTags.PRIVACY_URL) }
            ListItem(
                onClick = { uri.openUri(AboutTags.LICENSE_URL) },
                modifier = Modifier.testTag(AboutTags.LICENSE),
                leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.about_license_mit)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            ) { Text(stringResource(R.string.about_license)) }
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, url: String, tag: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(url.removePrefix("https://")) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
    ) { Text(title) }
}
