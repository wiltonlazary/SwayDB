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

package swaydb.core.map.timer

import java.nio.file.Path

import swaydb.IO
import swaydb.core.data.Time
import swaydb.core.function.FunctionStore
import swaydb.core.map.MapEntry
import swaydb.core.map.serializer.{MapEntryReader, MapEntryWriter}
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice

private[core] trait Timer {
  val empty: Boolean

  def next: Time

  def close: Unit
}

private[core] object Timer {
  val defaultKey = Slice.emptyBytes

  def memory(): MemoryTimer =
    MemoryTimer()

  def empty: Timer =
    EmptyTimer

  def persistent(path: Path,
                 mmap: Boolean,
                 mod: Long,
                 flushCheckpointSize: Long)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                            timeOrder: TimeOrder[Slice[Byte]],
                                            functionStore: FunctionStore,
                                            writer: MapEntryWriter[MapEntry.Put[Slice[Byte], Slice[Byte]]],
                                            reader: MapEntryReader[MapEntry[Slice[Byte], Slice[Byte]]]): IO[swaydb.Error.Map, PersistentTimer] =
    PersistentTimer(
      path = path,
      mmap = mmap,
      mod = mod,
      flushCheckpointSize = flushCheckpointSize
    )
}
