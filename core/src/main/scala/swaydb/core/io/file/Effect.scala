/*
 * Copyright (c) 2019 Simer Plaha (@simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.core.io.file

import java.io.IOException
import java.nio.channels.{FileLock, WritableByteChannel}
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import com.typesafe.scalalogging.LazyLogging
import swaydb.Error.IO.ExceptionHandler
import swaydb.IO
import swaydb.IO._
import swaydb.core.util.Extension
import swaydb.core.util.PipeOps._
import swaydb.data.slice.Slice

import scala.collection.JavaConverters._

private[core] object Effect extends LazyLogging {

  implicit class PathExtensionImplicits(path: Path) {
    def fileId =
      Effect.fileId(path)

    def incrementFileId =
      Effect.incrementFileId(path)

    def incrementFolderId =
      Effect.incrementFolderId(path)

    def folderId =
      Effect.folderId(path)

    def files(extension: Extension): List[Path] =
      Effect.files(path, extension)

    def folders =
      Effect.folders(path)

    def exists =
      Effect.exists(path)
  }

  def write(to: Path,
            bytes: Slice[Byte]): IO[swaydb.Error.IO, Path] =
    IO(Files.newByteChannel(to, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) flatMap {
      channel =>
        try {
          writeUnclosed(channel, bytes) map {
            _ =>
              to
          }
        } finally {
          channel.close()
        }
    }

  def write(to: Path,
            bytes: Iterable[Slice[Byte]]): IO[swaydb.Error.IO, Path] =
    IO(Files.newByteChannel(to, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) flatMap {
      channel =>
        try {
          writeUnclosed(channel, bytes) map {
            _ =>
              to
          }
        } finally {
          channel.close()
        }
    }

  def replace(bytes: Slice[Byte],
              to: Path): IO[swaydb.Error.IO, Path] =
    IO(Files.newByteChannel(to, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) flatMap {
      channel =>
        try {
          writeUnclosed(channel, bytes) map {
            _ =>
              to
          }
        } finally {
          channel.close()
        }
    }

  def writeUnclosed(channel: WritableByteChannel,
                    bytes: Iterable[Slice[Byte]]): IO[swaydb.Error.IO, Unit] =
    try
      bytes foreachIO {
        bytes =>
          writeUnclosed(channel, bytes)
      } getOrElse IO.unit
    catch {
      case exception: Exception =>
        IO.failed(exception)
    }

  def writeUnclosed(channel: WritableByteChannel,
                    bytes: Slice[Byte]): IO[swaydb.Error.IO, Unit] =
    try {
      val written = channel write bytes.toByteBufferWrap

      // toByteBuffer uses size of Slice instead of written,
      // but here the check on written ensures that only the actually written bytes find written.
      // All the client code invoking writes to Disk using Slice should ensure that no Slice contains empty bytes.
      if (written != bytes.size)
        IO.failed(swaydb.Exception.FailedToWriteAllBytes(written, bytes.size, bytes.size))
      else
        IO.unit
    } catch {
      case exception: Exception =>
        IO.failed(exception)
    }

  def copy(copyFrom: Path,
           copyTo: Path): IO[swaydb.Error.IO, Path] =
    IO {
      Files.copy(copyFrom, copyTo)
    }

  def delete(path: Path): IO[swaydb.Error.IO, Unit] =
    IO(Files.delete(path))

  def deleteIfExists(path: Path): IO[swaydb.Error.IO, Unit] =
    if (exists(path))
      delete(path)
    else
      IO.unit

  def createFile(path: Path): IO[swaydb.Error.IO, Path] =
    IO {
      Files.createFile(path)
    }

  def createFileIfAbsent(path: Path): IO[swaydb.Error.IO, Path] =
    if (exists(path))
      IO.Right(path)
    else
      createFile(path)

  def exists(path: Path) =
    Files.exists(path)

  def notExists(path: Path) =
    !exists(path)

  def createDirectoryIfAbsent(path: Path): Path =
    if (exists(path))
      path
    else
      Files.createDirectory(path)

  def createDirectoriesIfAbsent(path: Path): Path =
    if (exists(path))
      path
    else
      Files.createDirectories(path)

  def walkDelete(folder: Path): IO[swaydb.Error.IO, Unit] =
    IO {
      if (exists(folder))
        Files.walkFileTree(folder, new SimpleFileVisitor[Path]() {
          @throws[IOException]
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            if (exc != null) throw exc
            Files.delete(dir)
            FileVisitResult.CONTINUE
          }
        })
    }

  def release(lock: FileLock): IO[swaydb.Error.IO, Unit] =
    IO {
      lock.release()
      lock.close()
    }

  def stream[T](path: Path)(f: DirectoryStream[Path] => T): T = {
    val stream: DirectoryStream[Path] = Files.newDirectoryStream(path)
    try
      f(stream)
    finally
      stream.close()
  }

  def release(lock: Option[FileLock]): IO[swaydb.Error.IO, Unit] =
    lock.map(release) getOrElse IO.unit

  implicit class FileIdImplicits(id: Long) {
    def toLogFileId =
      s"$id.${Extension.Log}"

    def toFolderId =
      s"$id"

    def toSegmentFileId =
      s"$id.${Extension.Seg}"
  }

  def incrementFileId(path: Path): IO[swaydb.Error.IO, Path] =
    fileId(path) map {
      case (id, ext) =>
        path.getParent.resolve((id + 1) + "." + ext.toString)
    }

  def incrementFolderId(path: Path): Path =
    folderId(path) ==> {
      currentFolderId =>
        path.getParent.resolve((currentFolderId + 1).toString)
    }

  def folderId(path: Path): Long =
    path.getFileName.toString.toLong

  def fileId(path: Path): IO[swaydb.Error.IO, (Long, Extension)] = {
    val fileName = path.getFileName.toString
    val extensionIndex = fileName.lastIndexOf(".")
    val extIndex = if (extensionIndex <= 0) fileName.length else extensionIndex

    IO(fileName.substring(0, extIndex).toLong) orElse IO.failed(swaydb.Exception.NotAnIntFile(path)) flatMap {
      fileId =>
        val ext = fileName.substring(extIndex + 1, fileName.length)
        if (ext == Extension.Log.toString)
          IO.Right(fileId, Extension.Log)
        else if (ext == Extension.Seg.toString)
          IO.Right(fileId, Extension.Seg)
        else {
          logger.error("Unknown extension for file {}", path)
          IO.failed(swaydb.Exception.UnknownExtension(path))
        }
    }
  }

  def isExtension(path: Path, ext: Extension): Boolean =
    fileId(path).map(_._2 == ext) getOrElse false

  def files(folder: Path,
            extension: Extension): List[Path] =
    Effect.stream(folder) {
      _.iterator()
        .asScala
        .filter(isExtension(_, extension))
        .toList
        .sortBy(path => fileId(path).get._1)
    }

  def folders(folder: Path): List[Path] =
    Effect.stream(folder) {
      _.iterator()
        .asScala
        .filter(folder => IO(folderId(folder)).isRight)
        .toList
        .sortBy(folderId)
    }

  def segmentFilesOnDisk(paths: Seq[Path]): Seq[Path] =
    paths
      .flatMap(_.files(Extension.Seg))
      .sortBy(_.getFileName.fileId.get._1)

  def readAll(path: Path): IO[swaydb.Error.IO, Slice[Byte]] =
    IO(Slice(Files.readAllBytes(path)))

  def size(path: Path): IO[swaydb.Error.IO, Long] =
    IO(Files.size(path))
}