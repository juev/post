package org.evsyukov.post

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Permissions section of the README names every permission of the merged manifest (distribution.md). */
@RunWith(AndroidJUnit4::class)
class ReadmePermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun permissionsSection(): String {
        val readme = listOf(File("README.md"), File("../README.md")).first { it.isFile }.readText()
        val start = readme.indexOf("## Permissions")
        check(start >= 0) { "README has no Permissions section" }
        val end = readme.indexOf("\n## ", start + 1).takeIf { it > 0 } ?: readme.length
        return readme.substring(start, end)
    }

    @Test
    fun everyRequestedPermissionIsNamedInTheReadme() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            .map { it.substringAfterLast('.') }
            // Internal to the app, granted by its own signature; no user ever sees it.
            .filter { it != "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" }
        val section = permissionsSection()

        assertThat(requested).containsAtLeast("INTERNET", "USE_EXACT_ALARM", "WAKE_LOCK")
        assertThat(requested.filter { !Regex("\\b$it\\b").containsMatchIn(section) }).isEmpty()
    }
}
