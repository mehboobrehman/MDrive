package com.architect.driveaggregator.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

object SignatureVerifier {
    private const val TAG = "SignatureVerifier"

    fun verifySignature(context: Context, apkFile: File): Boolean {
        try {
            val pm = context.packageManager
            val installedPackageName = context.packageName

            val installedSignatures = getPackageSignatures(pm, installedPackageName)
            if (installedSignatures.isEmpty()) {
                Log.w(TAG, "Installed package signatures empty")
                return false
            }

            val apkSignatures = getApkSignatures(pm, apkFile)
            if (apkSignatures.isEmpty()) {
                Log.w(TAG, "APK package signatures empty")
                return false
            }

            val installedDigests = installedSignatures.map { computeSha256(it) }.toSet()
            val apkDigests = apkSignatures.map { computeSha256(it) }.toSet()

            return installedDigests.intersect(apkDigests).isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying APK signature", e)
            return false
        }
    }

    private fun getPackageSignatures(pm: PackageManager, packageName: String): Array<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = pkgInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }
            signatures?.map { it.toByteArray() }?.toTypedArray() ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.map { it.toByteArray() }?.toTypedArray() ?: emptyArray()
        }
    }

    private fun getApkSignatures(pm: PackageManager, apkFile: File): Array<ByteArray> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return emptyArray()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptyArray()
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures?.map { it.toByteArray() }?.toTypedArray() ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.map { it.toByteArray() }?.toTypedArray() ?: emptyArray()
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
