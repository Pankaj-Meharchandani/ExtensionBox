package com.extensionbox.app

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class UpdateRelease(
    val version: String,
    val message: String,
    val pageUrl: String
)

private data class GithubRelease(
    @SerializedName("tag_name") val tagName: String?,
    val body: String?,
    @SerializedName("html_url") val htmlUrl: String?
)

private interface GithubReleasesApi {
    @GET("repos/Pankaj-Meharchandani/ExtensionBox/releases/latest")
    suspend fun latestRelease(): GithubRelease
}

object UpdateReleaseChecker {
    private val api: GithubReleasesApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GithubReleasesApi::class.java)
    }

    suspend fun check(currentVersion: String): UpdateRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val latest = api.latestRelease()
            val version = latest.tagName.orEmpty().removePrefix("v").trim()
            if (version.isBlank() || compareVersions(version, currentVersion) <= 0) return@runCatching null
            UpdateRelease(
                version = version,
                message = latest.body?.trim().orEmpty(),
                pageUrl = latest.htmlUrl
                    ?: "https://github.com/Pankaj-Meharchandani/ExtensionBox/releases/latest"
            )
        }.getOrNull()
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }
}
