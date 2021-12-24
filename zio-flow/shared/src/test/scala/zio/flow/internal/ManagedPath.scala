package zio.flow.internal

import zio.{ Task, URIO, ZManaged }

import java.io.File
import java.nio.file.{ Files, Path }

object ManagedPath {
  private def createTempDirectory: Task[Path] = Task {
    Files.createTempDirectory("zio-rocksdb")
  }

  private def deleteDirectory(path: Path): Task[Unit] =
    Task {
      def deleteRecursively(file: File): Unit = {
        if (file.isDirectory) {
          file.listFiles.foreach(deleteRecursively)
        }
        if (file.exists && !file.delete) {
          throw new Exception(s"Unable to delete ${file.getAbsolutePath}")
        }

        deleteRecursively(path.toFile)
      }
    }

  private def deleteDirectoryE(path: Path): URIO[Any, Unit] =
    deleteDirectory(path).orDie

  def apply(): ZManaged[Any, Throwable, Path] = createTempDirectory.toManaged(deleteDirectoryE)
}
