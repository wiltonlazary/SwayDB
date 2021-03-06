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

package swaydb.core.segment.format.a

import org.scalatest.OptionValues._
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data.Value.{FromValue, RangeValue}
import swaydb.core.data.{KeyValue, Memory, Value}
import swaydb.core.io.file.Effect._
import swaydb.core.segment.{Segment, SegmentAssigner, SegmentIO}
import swaydb.core.util.PipeOps._
import swaydb.core.{TestBase, TestTimer}
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

import scala.collection.mutable
import scala.concurrent.duration._

class SegmentAssignerSpec0 extends SegmentAssignerSpec {
  val keyValueCount = 100
}

class SegmentAssignerSpec1 extends SegmentAssignerSpec {
  val keyValueCount = 100

  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = true
  override def mmapSegmentsOnRead = true
  override def level0MMAP = true
  override def appendixStorageMMAP = true
}

class SegmentAssignerSpec2 extends SegmentAssignerSpec {
  val keyValueCount = 100

  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = false
  override def mmapSegmentsOnRead = false
  override def level0MMAP = false
  override def appendixStorageMMAP = false
}

class SegmentAssignerSpec3 extends SegmentAssignerSpec {
  val keyValueCount = 1000
  override def inMemoryStorage = true
}

sealed trait SegmentAssignerSpec extends TestBase {
  implicit val keyOrder = KeyOrder.default
  implicit val testTimer: TestTimer = TestTimer.Empty
  implicit def segmentIO: SegmentIO = SegmentIO.random

  def keyValueCount: Int

  "SegmentAssign.assign" should {

    "assign KeyValues to the first Segment if there is only one Segment" in {
      val keyValues = randomizedKeyValues(keyValueCount)

      val segment = TestSegment()

      val result = SegmentAssigner.assignUnsafe(keyValues, List(segment))
      result.size shouldBe 1
      result.keys.head.path shouldBe segment.path
      result.values.head shouldBe keyValues
    }

    "assign KeyValues to second Segment when none of the keys belong to the first Segment" in {
      val segment1 = TestSegment(Slice(Memory.put(1), Memory.Range(2, 10, Value.FromValue.Null, Value.remove(10.seconds.fromNow))))
      val segment2 = TestSegment(Slice(Memory.put(10)))
      val segments = Seq(segment1, segment2)

      val result =
        SegmentAssigner.assignUnsafe(
          keyValues =
            Slice(
              randomFixedKeyValue(10),
              randomRangeKeyValue(11, 20),
              randomFixedKeyValue(20)
            ),
          segments = segments
        )

      result.size shouldBe 1
      result.keys.head.path shouldBe segment2.path
    }

    "assign gap KeyValue to the first Segment if the first Segment already has a key-value assigned to it" in {
      val segment1 = TestSegment(Slice(randomFixedKeyValue(1), randomRangeKeyValue(2, 10)))
      val segment2 = TestSegment(Slice(randomFixedKeyValue(20)))
      val segments = Seq(segment1, segment2)

      //1 belongs to first Segment, 15 is a gap key and since first segment is not empty, it will value assigned 15.
      val keyValues =
        Slice(
          Memory.put(1, 1),
          Memory.put(15),
          Memory.Range(16, 20, Value.FromValue.Null, Value.update(16))
        )

      val result = SegmentAssigner.assignUnsafe(keyValues, segments)
      result.size shouldBe 1
      result.keys.head.path shouldBe segment1.path
      result.values.head shouldBe keyValues
    }

    "assign gap KeyValue to the second Segment if the first Segment has no key-value assigned to it" in {
      runThis(10.times) {
        val segment1KeyValues = Slice(randomFixedKeyValue(1), randomRangeKeyValue(2, 10))
        val segment2KeyValues = Slice(randomFixedKeyValue(20))

        val segment1 = TestSegment(segment1KeyValues)
        val segment2 = TestSegment(segment2KeyValues)
        val segments = Seq(segment1, segment2)

        //15 is a gap key but no key-values are assigned to segment1 so segment2 will value this key-value.
        val keyValues =
          Slice(
            randomFixedKeyValue(15),
            randomRangeKeyValue(20, 100)
          )

        val result = SegmentAssigner.assignUnsafe(keyValues, segments)
        result.size shouldBe 1
        result.keys.head.path shouldBe segment2.path
        result.values.head shouldBe keyValues
      }
    }

    "assign gap Range KeyValue to all Segments that fall within the Range's toKey" in {
      // 1 - 10(exclusive)
      val segment1 = TestSegment(Slice(Memory.put(1), Memory.Range(2, 10, Value.FromValue.Null, Value.remove(None))))
      // 20 - 20
      val segment2 = TestSegment(Slice(Memory.remove(20)))
      //21 - 30
      val segment3 = TestSegment(Slice(Memory.Range(21, 30, Value.FromValue.Null, Value.remove(None)), Memory.put(30)))
      //40 - 60
      val segment4 = TestSegment(Slice(Memory.remove(40), Memory.Range(41, 50, Value.FromValue.Null, Value.remove(None)), Memory.put(60)))
      //70 - 80
      val segment5 = TestSegment(Slice(Memory.put(70), Memory.remove(80)))
      val segments = Seq(segment1, segment2, segment3, segment4, segment5)

      //15 is a gap key but no key-values are assigned to segment1 so segment2 will value this key-value an it will be split across.
      //all next overlapping Segments.
      val keyValues =
      Slice(
        Memory.Range(15, 50, Value.remove(None), Value.update(10))
      )

      def assertResult(assignments: mutable.Map[Segment, Slice[KeyValue]]) = {
        assignments.size shouldBe 3
        assignments.find(_._1 == segment2).value._2 should contain only Memory.Range(15, 21, Value.remove(None), Value.update(10))
        assignments.find(_._1 == segment3).value._2 should contain only Memory.Range(21, 40, Value.FromValue.Null, Value.update(10))
        assignments.find(_._1 == segment4).value._2 should contain only Memory.Range(40, 50, Value.FromValue.Null, Value.update(10))
      }

      assertResult(SegmentAssigner.assignUnsafe(keyValues, segments))
    }

    "assign key value to the first segment when the key is the new smallest" in {
      val segment1 = TestSegment(Slice(randomFixedKeyValue(1), randomFixedKeyValue(2)))
      val segment2 = TestSegment(Slice(randomFixedKeyValue(4), randomFixedKeyValue(5)))

      //segment1 - 1 - 2
      //segment2 - 4 - 5
      val segments = Seq(segment1, segment2)

      SegmentAssigner.assignUnsafe(Slice(Memory.put(0)), segments) ==> {
        result =>
          result.size shouldBe 1
          result.keys.head.path shouldBe segment1.path
      }
    }

    "assign key value to the first segment and split out to other Segment when the key is the new smallest and the range spreads onto other Segments" in {
      val segment1 = TestSegment(Slice(Memory.put(1), Memory.put(2)))
      val segment2 = TestSegment(Slice(Memory.put(4), Memory.put(5)))
      val segment3 = TestSegment(Slice(Memory.Range(6, 10, Value.remove(None), Value.update(10)), Memory.remove(10)))

      //segment1 - 1 - 2
      //segment2 - 4 - 5
      //segment3 - 6 - 10
      val segments = Seq(segment1, segment2, segment3)

      //insert range 0 - 20. This overlaps all 3 Segment and key-values will value sliced and distributed to all Segments.
      SegmentAssigner.assignUnsafe(Slice(Memory.Range(0, 20, Value.put(0), Value.remove(None))), segments) ==> {
        assignments =>
          assignments.size shouldBe 3
          assignments.find(_._1 == segment1).value._2 should contain only Memory.Range(0, 4, Value.put(0), Value.remove(None))
          assignments.find(_._1 == segment2).value._2 should contain only Memory.Range(4, 6, Value.FromValue.Null, Value.remove(None))
          assignments.find(_._1 == segment3).value._2 should contain only Memory.Range(6, 20, Value.FromValue.Null, Value.remove(None))
      }
    }

    "debugger" in {
      val segment1 = TestSegment(Slice(Memory.put(1), Memory.Range(26074, 26075, Value.FromValue.Null, Value.update(Slice.Null, None))))
      val segment2 = TestSegment(Slice(Memory.put(26075), Memory.Range(28122, 28123, Value.FromValue.Null, Value.update(Slice.Null, None))))
      val segment3 = TestSegment(Slice(Memory.put(28123), Memory.Range(32218, 32219, Value.FromValue.Null, Value.update(Slice.Null, None))))
      val segment4 = TestSegment(Slice(Memory.put(32219), Memory.Range(40410, 40411, Value.FromValue.Null, Value.update(Slice.Null, None))))
      val segment5 = TestSegment(Slice(Memory.put(74605), Memory.put(100000)))

      val segments = Seq(segment1, segment2, segment3, segment4, segment5)

      SegmentAssigner.assignUnsafe(Slice(Memory.put(1), Memory.put(100000)), segments) ==> {
        assignments =>
          assignments.size shouldBe 2
          assignments.find(_._1 == segment1).value._2 should contain only Memory.put(1)
          assignments.find(_._1 == segment5).value._2 should contain only Memory.put(100000)
      }
    }

    "assign key value to the last segment when the key is the new largest" in {
      val segment1 = TestSegment(Slice(Memory.put(1), Memory.put(2)))
      val segment2 = TestSegment(Slice(Memory.put(4), Memory.put(5)))
      val segment3 = TestSegment(Slice(Memory.put(6), Memory.put(7)))
      val segment4 = TestSegment(Slice(Memory.put(8), Memory.put(9)))
      val segments = Seq(segment1, segment2, segment3, segment4)

      SegmentAssigner.assignUnsafe(Slice(Memory.put(10, "ten")), segments) ==> {
        result =>
          result.size shouldBe 1
          result.keys.head.path shouldBe segment4.path
          result.values.head should contain only Memory.put(10, "ten")
      }

      SegmentAssigner.assignUnsafe(Slice(Memory.remove(10)), segments) ==> {
        result =>
          result.size shouldBe 1
          result.keys.head.path shouldBe segment4.path
          result.values.head should contain only Memory.remove(10)
      }

      SegmentAssigner.assignUnsafe(Slice(Memory.Range(10, 20, Value.put(10), Value.remove(None))), segments) ==> {
        result =>
          result.size shouldBe 1
          result.keys.head.path shouldBe segment4.path
          result.values.head should contain only Memory.Range(10, 20, Value.put(10), Value.remove(None))
      }
    }

    "assign all KeyValues to their target Segments" in {
      val keyValues = Slice(randomFixedKeyValue(1), randomFixedKeyValue(2), randomFixedKeyValue(3), randomFixedKeyValue(4), randomFixedKeyValue(5))
      val segment1 = TestSegment(Slice(randomFixedKeyValue(key = 1)))
      val segment2 = TestSegment(Slice(randomFixedKeyValue(key = 2)))
      val segment3 = TestSegment(Slice(randomFixedKeyValue(key = 3)))
      val segment4 = TestSegment(Slice(randomFixedKeyValue(key = 4)))
      val segment5 = TestSegment(Slice(randomFixedKeyValue(key = 5)))

      val segments = List(segment1, segment2, segment3, segment4, segment5)

      val result = SegmentAssigner.assignUnsafe(keyValues, segments)
      result.size shouldBe 5

      //sort them by the fileId, so it's easier to test
      val resultArray = result.toArray.sortBy(_._1.path.fileId._1)

      resultArray(0)._1.path shouldBe segment1.path
      resultArray(0)._2 should have size 1
      resultArray(0)._2.head.key shouldBe (1: Slice[Byte])

      resultArray(1)._1.path shouldBe segment2.path
      resultArray(1)._2 should have size 1
      resultArray(1)._2.head.key shouldBe (2: Slice[Byte])

      resultArray(2)._1.path shouldBe segment3.path
      resultArray(2)._2 should have size 1
      resultArray(2)._2.head.key shouldBe (3: Slice[Byte])

      resultArray(3)._1.path shouldBe segment4.path
      resultArray(3)._2 should have size 1
      resultArray(3)._2.head.key shouldBe (4: Slice[Byte])

      resultArray(4)._1.path shouldBe segment5.path
      resultArray(4)._2 should have size 1
      resultArray(4)._2.head.key shouldBe (5: Slice[Byte])
    }
  }
}
