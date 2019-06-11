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

package swaydb.core.level

import java.nio.file.NoSuchFileException

import org.scalamock.scalatest.MockFactory
import org.scalatest.PrivateMethodTester
import swaydb.core.CommonAssertions._
import swaydb.core.IOAssert._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data._
import swaydb.core.group.compression.data.KeyValueGroupingStrategyInternal
import swaydb.core.level.zero.LevelZeroSkipListMerger
import swaydb.core.queue.{FileLimiter, KeyValueLimiter}
import swaydb.core.segment.Segment
import swaydb.core.{TestBase, TestData, TestLimitQueues, TestTimer}
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice
import swaydb.data.util.StorageUnits._

class LevelCopySpec0 extends LevelCopySpec

class LevelCopySpec1 extends LevelCopySpec {
  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = true
  override def mmapSegmentsOnRead = true
  override def level0MMAP = true
  override def appendixStorageMMAP = true
}

class LevelCopySpec2 extends LevelCopySpec {
  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = false
  override def mmapSegmentsOnRead = false
  override def level0MMAP = false
  override def appendixStorageMMAP = false
}

class LevelCopySpec3 extends LevelCopySpec {
  override def inMemoryStorage = true
}

sealed trait LevelCopySpec extends TestBase with MockFactory with PrivateMethodTester {

  implicit val keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default
  implicit val testTimer: TestTimer = TestTimer.Empty
  implicit val timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long
  val keyValuesCount = 100

  //  override def deleteFiles: Boolean =
  //    false

  implicit val maxSegmentsOpenCacheImplicitLimiter: FileLimiter = TestLimitQueues.fileOpenLimiter
  implicit val keyValuesLimitImplicitLimiter: KeyValueLimiter = TestLimitQueues.keyValueLimiter
  implicit val groupingStrategy: Option[KeyValueGroupingStrategyInternal] = randomGroupingStrategyOption(keyValuesCount)
  implicit val skipListMerger = LevelZeroSkipListMerger

  "copy" should {
    "copy segments" in {
      val level = TestLevel()
      level.isEmpty shouldBe true

      val keyValues1 = randomIntKeyStringValues()
      val keyValues2 = randomIntKeyStringValues()
      val segments = Iterable(TestSegment(keyValues1).assertGet, TestSegment(keyValues2).assertGet)
      val copiedSegments = level.copy(segments).assertGet

      val allKeyValues = Slice((keyValues1 ++ keyValues2).toArray).updateStats

      level.isEmpty shouldBe true //copy function does not write to appendix.

      if (persistent) level.segmentFilesOnDisk should not be empty

      Segment.getAllKeyValues(TestData.falsePositiveRate, copiedSegments).assertGet shouldBe allKeyValues
    }

    "fail copying Segments if it failed to copy one of the Segments" in {
      val level = TestLevel()
      level.isEmpty shouldBe true

      val segment1 = TestSegment().assertGet
      val segment2 = TestSegment().assertGet

      segment2.delete.assertGet // delete segment2 so there is a failure in copying Segments

      val segments = Iterable(segment1, segment2)
      level.copy(segments).failed.assertGet.exception shouldBe a[NoSuchFileException]

      level.isEmpty shouldBe true
      if (persistent) level.reopen.isEmpty shouldBe true
    }

    "copy Map" in {
      val level = TestLevel()
      level.isEmpty shouldBe true

      val keyValues = randomPutKeyValues(keyValuesCount).asInstanceOf[Slice[Memory.SegmentResponse]]
      val copiedSegments = level.copy(TestMap(keyValues)).assertGet
      level.isEmpty shouldBe true //copy function does not write to appendix.

      if (persistent) level.segmentFilesOnDisk should not be empty

      Segment.getAllKeyValues(TestData.falsePositiveRate, copiedSegments).assertGet shouldBe keyValues
    }
  }

  "copy map directly into lower level" in {
    val level2 = TestLevel(segmentSize = 1.kb)
    val level1 = TestLevel(segmentSize = 1.kb, nextLevel = Some(level2))

    val keyValues = randomPutKeyValues(keyValuesCount, addRandomExpiredPutDeadlines = false)
    val maps = TestMap(keyValues.toTransient.toMemoryResponse)

    level1.put(maps).assertGet

    level1.isEmpty shouldBe true
    level2.isEmpty shouldBe false

    assertReads(keyValues, level1)

    level1.segmentsInLevel() foreach (_.createdInLevel.assertGet shouldBe level2.levelNumber)
  }

}