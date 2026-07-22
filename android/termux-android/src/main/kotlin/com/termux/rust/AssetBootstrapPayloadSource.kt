package com.termux.rust

import android.content.res.AssetManager
import java.io.InputStream

/**
 * Bundled bootstrap payload read from app assets. Layout:
 * `<asset>` (zip), `<asset>.sha256` (64-hex), `<asset>.version` (non-empty).
 *
 * A missing archive opens nothing — the installer surfaces MISSING. A
 * present archive with missing or malformed sidecar metadata is a CORRUPT
 * failure: unverified payloads are never installed.
 */
internal class AssetBootstrapPayloadSource(
    private val assets: AssetManager,
    private val assetPath: String,
) : BootstrapPayloadSource {
    override val version: String by lazy {
        validateVersion(readTextOrNull("$assetPath.version"))
    }
    override val expectedSha256: String? by lazy {
        validateSha256(readTextOrNull("$assetPath.sha256"))
    }

    override fun open(): InputStream? =
        runCatching { assets.open(assetPath) }.getOrNull()

    private fun readTextOrNull(path: String): String? =
        runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()

    internal companion object {
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        fun validateVersion(raw: String?): String {
            val value = raw?.trim()
            if (value.isNullOrEmpty()) {
                throw BootstrapInstallException(
                    BootstrapFailure.CORRUPT,
                    "bootstrap .version sidecar is missing or empty",
                )
            }
            return value
        }

        fun validateSha256(raw: String?): String {
            val value = raw?.trim()
            if (value == null || !SHA256_PATTERN.matches(value)) {
                throw BootstrapInstallException(
                    BootstrapFailure.CORRUPT,
                    "bootstrap .sha256 sidecar is missing or not 64-hex",
                )
            }
            return value
        }
    }
}
