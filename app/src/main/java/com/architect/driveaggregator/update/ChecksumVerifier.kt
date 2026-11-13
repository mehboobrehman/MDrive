package com.architect.driveaggregator.update

import java.io.File
import java.security.MessageDigest

object ChecksumVerifier {
    fun verify(apkFile: File, checksumsContent: String, expectedFilename: String): Boolean {
        if (!apkFile.exists()) return false
        val expectedHash = extractChecksum(checksumsContent, expectedFilename) ?: return false
        val actualHash = computeSha256(apkFile)
        return expectedHash.equals(actualHash, ignoreCase = true)
    }

    fun extractChecksum(checksumsContent: String, targetFilename: String): String? {
        val lines = checksumsContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val hash = parts[0]
                val filename = parts[1].removePrefix("*")
                if (filename.equals(targetFilename, ignoreCase = true) || filename.endsWith("/$targetFilename")) {
                    if (hash.length == 64 && hash.all { it in "0123456789abcdefABCDEF" }) {
                        return hash
                    }
                }
            }
        }
        return null
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
