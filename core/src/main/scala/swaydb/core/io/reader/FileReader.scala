/*
 * Copyright (c) 2020 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
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

package swaydb.core.io.reader

import com.typesafe.scalalogging.LazyLogging
import swaydb.core.io.file.DBFile
import swaydb.data.slice.{Reader, Slice}

private[core] class FileReader(val file: DBFile) extends Reader with LazyLogging {

  private var position: Int = 0

  def isLoaded: Boolean =
    file.isLoaded

  override def size: Long =
    file.fileSize

  def moveTo(newPosition: Long): FileReader = {
    position = newPosition.toInt max 0
    this
  }

  def moveTo(newPosition: Int): FileReader = {
    position = newPosition.toInt max 0
    this
  }

  def hasMore: Boolean =
    position < size

  def hasAtLeast(size: Long): Boolean =
    (file.fileSize - position) >= size

  override def copy(): FileReader =
    new FileReader(file)

  override def getPosition: Int = position

  override def get() = {
    val byte = file get position
    position += 1
    byte
  }

  override def read(size: Int) =
    if (size <= 0) {
      Slice.emptyBytes
    } else {
      val bytes = file.read(position, size)
      position += size
      bytes
    }

  def path =
    file.path

  override def readRemaining(): Slice[Byte] =
    read(remaining)

  final override val isFile: Boolean =
    true
}