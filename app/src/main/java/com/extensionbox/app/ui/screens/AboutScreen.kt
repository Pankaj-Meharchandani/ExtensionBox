package com.extensionbox.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extensionbox.app.BuildConfig
import com.extensionbox.app.R
import com.extensionbox.app.network.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private data class ContributorInfo(
    val nameStringRes: Int,
    val username: String,
    val githubUrl: String,
    val accentColor: Color
)

private val CONTRIBUTORS = listOf(
    ContributorInfo(
        nameStringRes = R.string.pankaj_meharchandani,
        username = "Pankaj-Meharchandani",
        githubUrl = "https://github.com/Pankaj-Meharchandani",
        accentColor = Color(0xFFAB47BC) // Violet
    ),
    ContributorInfo(
        nameStringRes = R.string.suvojeet_sengupta,
        username = "suvojeet-sengupta",
        githubUrl = "https://github.com/suvojeet-sengupta",
        accentColor = Color(0xFF26A69A) // Cyan/Teal
    ),
    ContributorInfo(
        nameStringRes = R.string.omer,
        username = "omersusin",
        githubUrl = "https://github.com/omersusin",
        accentColor = Color(0xFFFF9100) // Amber/Orange
    ),
    ContributorInfo(
        nameStringRes = R.string.juanma,
        username = "juanma0511",
        githubUrl = "https://github.com/juanma0511",
        accentColor = Color(0xFF2979FF) // Royal Blue
    )
)

object GitHubAvatarCache {
    suspend fun getAvatarBitmap(context: Context, username: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "github_avatar_$username.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) return@withContext bitmap
            } catch (_: Exception) {
                cacheFile.delete()
            }
        }

        try {
            val url = URL("https://github.com/$username.png")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            val responseCode = connection.responseCode
            if (responseCode in 200..299 || responseCode == 301 || responseCode == 302) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    try {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    } catch (_: Exception) {}
                    return@withContext bitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

@Composable
fun AboutScreen() {
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val checkForUpdates = stringResource(R.string.check_for_updates)
    var updateStatus by remember { mutableStateOf(checkForUpdates) }
    var isChecking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // --- Hero Header ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
            Surface(
                modifier = Modifier.size(86.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(id = R.string.logo),
                        modifier = Modifier.size(70.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(id = R.string.extension_box),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Text(
                    text = stringResource(id = R.string.version_name, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                )
            }
        }

        // --- Description Bento Card ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, Color(0xFFFF4081).copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        color = Color(0xFFFF4081).copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFFF4081),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "About ExtensionBox",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = stringResource(id = R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Software Update Bento Card ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF00E676).copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Update,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = stringResource(id = R.string.software_update),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = updateStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    val checkingGithub = stringResource(id = R.string.checking_github)
                    val latestVersionMessage = stringResource(id = R.string.latest_version_message)
                    val newVersionAvailable = stringResource(id = R.string.new_version_available)
                    val updateCheckFailed = stringResource(id = R.string.update_check_failed)

                    IconButton(
                        onClick = {
                            isChecking = true
                            updateStatus = checkingGithub
                            coroutineScope.launch {
                                try {
                                    val service = UpdateChecker.getGitHubService()
                                    val releases = service.getReleases("Pankaj-Meharchandani", "ExtensionBox")
                                    if (releases.isNotEmpty()) {
                                        val latest = releases[0].tagName
                                        updateStatus = if (latest.contains(BuildConfig.VERSION_NAME)) {
                                            latestVersionMessage
                                        } else {
                                            String.format(newVersionAvailable, latest)
                                        }
                                    }
                                } catch (e: Exception) {
                                    updateStatus = updateCheckFailed
                                } finally {
                                    isChecking = false
                                }
                            }
                        },
                        modifier = Modifier.background(Color(0xFF00E676).copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.check_for_updates),
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // --- Contributors Section Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.engineering_team),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "${CONTRIBUTORS.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // --- 2-Column Grid of Contributor Cards ---
        val chunkedContributors = CONTRIBUTORS.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chunkedContributors.forEach { rowList ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowList.forEach { contributor ->
                        ContributorGridCard(
                            contributor = contributor,
                            onClick = { uriHandler.openUri(contributor.githubUrl) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowList.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun ContributorGridCard(
    contributor: ContributorInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val name = stringResource(id = contributor.nameStringRes)
    var avatarBitmap by remember(contributor.username) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(contributor.username) {
        val bm = GitHubAvatarCache.getAvatarBitmap(context, contributor.username)
        if (bm != null) {
            avatarBitmap = bm.asImageBitmap()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(115.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, contributor.accentColor.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val bitmap = avatarBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = name,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = contributor.accentColor.copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = name.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = contributor.accentColor
                            )
                        }
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = contributor.accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = contributor.accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${contributor.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = contributor.accentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
