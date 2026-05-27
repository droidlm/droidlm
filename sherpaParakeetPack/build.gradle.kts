import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.asset-pack")
}

val sherpaParakeetModelName = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"
val sherpaParakeetArchiveName = "$sherpaParakeetModelName.tar.bz2"
val sherpaParakeetUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$sherpaParakeetArchiveName"
val sherpaParakeetSha256 = "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150"
val sherpaDownloadDir = layout.buildDirectory.dir("downloads/sherpa")
val sherpaAssetSourceDir = layout.projectDirectory.dir("src/main/assets")
val sherpaParakeetArchive = sherpaDownloadDir.map { it.file(sherpaParakeetArchiveName) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

val downloadSherpaParakeetModel by tasks.registering {
    outputs.file(sherpaParakeetArchive)
    doLast {
        val archive = sherpaParakeetArchive.get().asFile
        if (archive.isFile && sha256(archive).equals(sherpaParakeetSha256, ignoreCase = true)) return@doLast
        archive.parentFile.mkdirs()
        val temp = File(archive.parentFile, "${archive.name}.tmp")
        temp.delete()
        URI(sherpaParakeetUrl).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha256 = sha256(temp)
        require(actualSha256.equals(sherpaParakeetSha256, ignoreCase = true)) {
            temp.delete()
            "Downloaded Sherpa Parakeet model checksum mismatch"
        }
        archive.delete()
        require(temp.renameTo(archive)) { "Could not move downloaded Sherpa Parakeet archive" }
    }
}

val unpackSherpaParakeetModel by tasks.registering(Sync::class) {
    dependsOn(downloadSherpaParakeetModel)
    from(tarTree(resources.bzip2(sherpaParakeetArchive)))
    into(sherpaAssetSourceDir.dir("sherpa"))
}

assetPack {
    packName.set("sherpa_parakeet")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}

tasks.configureEach {
    if (name.contains("AssetPack", ignoreCase = true) || name.contains("Bundle", ignoreCase = true)) {
        dependsOn(unpackSherpaParakeetModel)
    }
}
