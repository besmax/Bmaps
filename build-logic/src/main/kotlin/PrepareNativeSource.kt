/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class PrepareNativeSource @Inject constructor(
    private val files: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:InputFile abstract val archiveFile: RegularFileProperty
    @get:Input abstract val archiveType: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun extract() {
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "Native source checksum mismatch" }
        files.sync {
            from(if (archiveType.get() == "zip") archives.zipTree(archiveFile) else archives.tarTree(archiveFile))
            into(destination)
        }
    }
}
