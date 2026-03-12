package io.locklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class RollbackHistoryService(private val project: Project) {

    data class RollbackEntry(
        val timestamp: String,
        val manifestBackup: Path,
        val planJson: Path,
        val description: String,
    )

    private val historyDir: Path
        get() {
            val base = Path.of(project.basePath ?: return Files.createTempDirectory("locklane"))
            val dir = base.resolve(".locklane").resolve("history")
            Files.createDirectories(dir)
            return dir
        }

    fun saveRollback(manifestPath: Path, planJsonPath: Path, updatesApplied: Int) {
        val ts = Instant.now()
        val tag = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(ts)
        val entryDir = historyDir.resolve(tag)
        Files.createDirectories(entryDir)

        Files.copy(manifestPath, entryDir.resolve(manifestPath.fileName))
        Files.copy(planJsonPath, entryDir.resolve("plan.json"))

        val meta = "timestamp=$ts\nmanifest=${manifestPath.fileName}\nupdates=$updatesApplied\n"
        Files.writeString(entryDir.resolve("meta.txt"), meta)
    }

    fun listEntries(): List<RollbackEntry> {
        val dir = historyDir
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .sorted(Comparator.reverseOrder())
                .map { entryDir ->
                    val metaFile = entryDir.resolve("meta.txt")
                    val props = if (Files.exists(metaFile)) {
                        Files.readAllLines(metaFile).associate {
                            val (k, v) = it.split("=", limit = 2)
                            k to v
                        }
                    } else emptyMap()
                    val manifestName = props["manifest"] ?: "unknown"
                    val updates = props["updates"] ?: "?"
                    val ts = props["timestamp"] ?: entryDir.fileName.toString()
                    val manifestBackup = entryDir.resolve(manifestName)
                    val planJson = entryDir.resolve("plan.json")
                    RollbackEntry(
                        timestamp = ts,
                        manifestBackup = manifestBackup,
                        planJson = planJson,
                        description = "$manifestName — $updates update(s) at ${entryDir.fileName}",
                    )
                }
                .toList()
        }
    }

    fun restore(entry: RollbackEntry, manifestPath: Path) {
        if (Files.exists(entry.manifestBackup)) {
            Files.copy(entry.manifestBackup, manifestPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteEntry(entry: RollbackEntry) {
        val dir = entry.manifestBackup.parent ?: return
        if (Files.isDirectory(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        fun getInstance(project: Project): RollbackHistoryService =
            project.getService(RollbackHistoryService::class.java)
    }
}
