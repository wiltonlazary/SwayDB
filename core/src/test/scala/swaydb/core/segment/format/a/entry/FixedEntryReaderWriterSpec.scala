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

package swaydb.core.segment.format.a.entry

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import swaydb.core.CommonAssertions._
import swaydb.IOValues._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.TestTimer
import swaydb.core.io.reader.Reader
import swaydb.core.segment.format.a.entry.reader.EntryReader
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

import scala.util.Random

class FixedEntryReaderWriterSpec extends WordSpec {

  implicit val keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default

  "write and read single Fixed entry" in {
    runThisParallel(1000.times) {
      implicit val testTimer = TestTimer.random
      val entry = randomFixedKeyValue(key = randomIntMax(), value = randomStringOption).toTransient
      //      println("write: " + entry)

      entry.valueEntryBytes.size should be <= 1

      val read =
        EntryReader.read(
          indexReader = Reader(entry.indexEntryBytes),
          mightBeCompressed = entry.stats.hasPrefixCompression,
          valueCache = entry.valueEntryBytes.headOption.map(buildSingleValueCache),
          indexOffset = 0,
          nextIndexOffset = 0,
          nextIndexSize = 0,
          accessPosition = entry.stats.thisKeyValueAccessIndexPosition,
          previous = None
        ).runIO
      //      println("read:  " + read)
      read shouldBe entry
    }
  }

  "write and read two fixed entries" in {
    runThis(1000.times) {
      implicit val testTimer = TestTimer.random

      val keyValues = randomizedKeyValues(count = 1, addPut = true, addGroups = false)
      val previous = keyValues.head

      previous.values.size should be <= 1

      val duplicateValues = if (Random.nextBoolean()) previous.values.headOption else randomStringOption
      val duplicateDeadline = if (Random.nextBoolean()) previous.deadline else randomDeadlineOption
      val next = randomFixedKeyValue(randomIntMax(), deadline = duplicateDeadline, value = duplicateValues).toTransient(previous = Some(previous))

      //      println("write previous: " + previous)
      //      println("write next: " + next)

      val valueBytes: Slice[Byte] = (previous.valueEntryBytes ++ next.valueEntryBytes).flatten.toSlice

      val previousRead =
        EntryReader.read(
          indexReader = Reader(previous.indexEntryBytes),
          mightBeCompressed = false,
          valueCache = Some(buildSingleValueCache(valueBytes)),
          indexOffset = 0,
          nextIndexOffset = 0,
          nextIndexSize = 0,
          accessPosition = previous.stats.thisKeyValueAccessIndexPosition,
          previous = None
        ).runIO

      previousRead shouldBe previous

      val nextRead =
        EntryReader.read(
          indexReader = Reader(next.indexEntryBytes),
          mightBeCompressed = next.stats.hasPrefixCompression,
          valueCache = Some(buildSingleValueCache(valueBytes)),
          indexOffset = 0,
          nextIndexOffset = 0,
          nextIndexSize = 0,
          accessPosition = next.stats.thisKeyValueAccessIndexPosition,
          previous = Some(previousRead)
        ).runIO

      //      val nextRead = EntryReader.read(Reader(next.indexEntryBytes), Reader(valueBytes), 0, 0, 0, Some(previousRead)).runIO
      nextRead shouldBe next

      //      println("read previous:  " + previousRead)
      //      println("read next:  " + nextRead)
      //      println
    }
  }
}
