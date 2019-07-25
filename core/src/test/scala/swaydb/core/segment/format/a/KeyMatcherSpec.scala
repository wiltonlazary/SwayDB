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

package swaydb.core.segment.format.a

import swaydb.core.CommonAssertions._
import swaydb.core.TestBase
import swaydb.core.TestData._
import swaydb.core.data.Persistent._
import swaydb.core.data.{Persistent, Time, Value}
import swaydb.core.segment.format.a.block.KeyMatcher.Result._
import swaydb.core.segment.format.a.block.{KeyMatcher, ValuesBlock}
import swaydb.core.util.cache.Cache
import swaydb.data.MaxKey
import swaydb.data.io.Core
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

import scala.util.Random

class KeyMatcherSpec extends TestBase {

  implicit val integer = new KeyOrder[Slice[Byte]] {
    def compare(a: Slice[Byte], b: Slice[Byte]): Int =
      IntSerializer.read(a).compareTo(IntSerializer.read(b))
  }

  /**
    * These implicits are just to make it easier to read the test cases.
    * The tests are normally for the match to Key in the following array
    *
    * -1, 0, 1, 2
    *
    * Tests check for keys to match in all positions (before and after each key)
    */

  val whichKeyValue = Random.shuffle((1 to 5).toList).head
  var isPrefixCompressed = randomBoolean()

  implicit def toFixed(int: Int): Persistent.Fixed =
    if (whichKeyValue == 1)
      Put(int, None, null, Time.empty, 0, 0, 0, 0, 0, 0, isPrefixCompressed)
    else if (whichKeyValue == 2)
      Update(int, None, null, Time.empty, 0, 0, 0, 0, 0, 0, isPrefixCompressed)
    else if (whichKeyValue == 3)
      Function(int, null, Time.empty, 0, 0, 0, 0, 0, 0, isPrefixCompressed)
    else if (whichKeyValue == 4)
      PendingApply(int, Time.empty, None, null, 0, 0, 0, 0, 0, 0, isPrefixCompressed)
    else
      Remove(int, None, Time.empty, 0, 0, 0, 0, isPrefixCompressed)

  implicit def toSomeFixed(int: Int): Option[Persistent.Fixed] =
    Some(int)

  object RangeImplicits {

    /**
      * Convenience implicits to make it easier to read the test cases.
      * A tuple indicates a range's (fromKey, toKey)
      */

    val noneRangeValueCache = Cache.valueIO[Core.Error.Private, ValuesBlock.Offset, (Option[Value.FromValue], Value.RangeValue)]((None, Value.Remove(None, Time.empty)))

    implicit def toRange(tuple: (Int, Int)): Persistent.Range =
      Persistent.Range(tuple._1, tuple._2, noneRangeValueCache, 0, 0, 0, 0, 0, 0, isPrefixCompressed)

    implicit def toSomeRange(tuple: (Int, Int)): Option[Persistent.Range] =
      Some(tuple)
  }

  object GroupImplicits {

    /**
      * Convenience implicits to make it easier to read the test cases.
      * A tuple (_1, _2) indicates a Groups minKey & maxKey where the maxKey is [[MaxKey.Fixed]]
      *
      * A tuple of tuple (_1, (_1, _2)) indicates a Groups minKey & maxKey where the maxKey is [[MaxKey.Range]]
      */
    implicit def toGroupFixed(tuple: (Int, Int)): Group =
      Group(
        _minKey = tuple._1,
        _maxKey = MaxKey.Fixed(tuple._2),
        segmentCache = null,
        nextIndexOffset = 0,
        nextIndexSize = 0,
        indexOffset = 0,
        valueOffset = 0,
        valueLength = 0,
        accessPosition = 0,
        deadline = None,
        isPrefixCompressed = isPrefixCompressed
      )

    implicit def toGroupRange(tuple: (Int, (Int, Int))): Group =
      Group(
        _minKey = tuple._1,
        _maxKey = MaxKey.Range(tuple._2._1, tuple._2._2),
        segmentCache = null,
        nextIndexOffset = 0,
        nextIndexSize = 0,
        indexOffset = 0,
        valueOffset = 0,
        valueLength = 0,
        accessPosition = 0,
        deadline = None,
        isPrefixCompressed = isPrefixCompressed
      )

    implicit def toSomeGroupFixed(tuple: (Int, Int)): Option[Group] =
      Some(tuple)

    implicit def toSomeGroupRange(tuple: (Int, (Int, Int))): Option[Group] =
      Some(tuple)
  }

  "KeyMatcher" should {
    "shouldFetchNext" should {
      "return false" when {
        "it's matchOnly" in {
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), Some(2)) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), Some(2)) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), Some(2)) shouldBe false

          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), None) shouldBe true

          import RangeImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), Some((2, 3))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), Some((2, 3))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), Some((2, 3))) shouldBe false

          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), None) shouldBe true

          import GroupImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), Some((2, (3, 4)))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), Some((2, (3, 4)))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), Some((2, (3, 4)))) shouldBe false

          KeyMatcher.shouldFetchNext(KeyMatcher.Get.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.SeekOne(1), None) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.SeekOne(1), None) shouldBe true
        }

        "it's whilePrefixCompressed" in {
          isPrefixCompressed = false
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), Some(2)) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), Some(2)) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), Some(2)) shouldBe false

          import RangeImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), Some((2, 3))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), Some((2, 3))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), Some((2, 3))) shouldBe false

          import GroupImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), Some((2, (3, 4)))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), Some((2, (3, 4)))) shouldBe false
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), Some((2, (3, 4)))) shouldBe false
        }
      }

      "return true" when {
        "it's WhilePrefixCompressed but next is None or next is isPrefixCompressed" in {
          isPrefixCompressed = true
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), eitherOne(None, Some(2))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), eitherOne(None, Some(2))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), eitherOne(None, Some(2))) shouldBe true

          import RangeImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), eitherOne(None, Some((2, 3)))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), eitherOne(None, Some((2, 3)))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), eitherOne(None, Some((2, 3)))) shouldBe true

          import GroupImplicits._
          KeyMatcher.shouldFetchNext(KeyMatcher.Get.WhilePrefixCompressed(1), eitherOne(None, Some((2, (3, 4))))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Higher.WhilePrefixCompressed(1), eitherOne(None, Some((2, (3, 4))))) shouldBe true
          KeyMatcher.shouldFetchNext(KeyMatcher.Lower.WhilePrefixCompressed(1), eitherOne(None, Some((2, (3, 4))))) shouldBe true
        }
      }
    }

    "Get" when {
      "Fixed" in {
        //-1
        //   0, 1, 2
        KeyMatcher.Get(-1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        //0
        //0, 1, 2
        KeyMatcher.Get(0).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Get(0).apply(previous = 0, next = None, hasMore = true) shouldBe Matched(None, 0, None)
        KeyMatcher.Get(0).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        //next should never be fetched if previous was a match. This should not occur in actual scenarios.
        //        KeyMatcher.Get(0).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0)
        //        KeyMatcher.Get(0).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(0)
        //        KeyMatcher.Get(0).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0)
        //        KeyMatcher.Get(0).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(0)

        //   1
        //0, 1, 2
        KeyMatcher.Get(1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(1).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Get(1).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Get(1).apply(previous = 1, next = None, hasMore = true) shouldBe Matched(None, 1, None)
        KeyMatcher.Get(1).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Get(1).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(0, 1, None)
        KeyMatcher.Get(1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        //      2
        //0, 1, 2
        KeyMatcher.Get(2).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(2).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Get(2).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(2).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Get(2).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(2).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Get(2).apply(previous = 1, next = 2, hasMore = false) shouldBe Matched(1, 2, None)
        KeyMatcher.Get(2).apply(previous = 1, next = 2, hasMore = true) shouldBe Matched(1, 2, None)
        KeyMatcher.Get(2).apply(previous = 2, next = None, hasMore = false) shouldBe Matched(None, 2, None)
        KeyMatcher.Get(2).apply(previous = 2, next = None, hasMore = true) shouldBe Matched(None, 2, None)

        //         3
        //0, 1, 2
        KeyMatcher.Get(3).apply(previous = 2, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(3).apply(previous = 2, next = None, hasMore = true) shouldBe BehindFetchNext(2)
        KeyMatcher.Get(3).apply(previous = 3, next = None, hasMore = false) shouldBe Matched(None, 3, None)
        KeyMatcher.Get(3).apply(previous = 3, next = None, hasMore = true) shouldBe Matched(None, 3, None)
        KeyMatcher.Get(3).apply(previous = 2, next = 3, hasMore = false) shouldBe Matched(2, 3, None)
        KeyMatcher.Get(3).apply(previous = 2, next = 3, hasMore = true) shouldBe Matched(2, 3, None)
        KeyMatcher.Get(3).apply(previous = 2, next = 4, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(3).apply(previous = 2, next = 4, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(3).apply(previous = 0, next = 4, hasMore = true) shouldBe AheadOrNoneOrEnd
      }

      //range tests
      "Range" in {
        import RangeImplicits._

        //-1
        KeyMatcher.Get(-1).apply(previous = 0, next = (5, 10), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = (5, 10), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(0).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(9).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(9).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Get(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(21).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(21).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(15).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(15).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd
      }

      //group tests
      "Group" in {
        import GroupImplicits._

        KeyMatcher.Get(-1).apply(previous = 0, next = (5, 10), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = (5, 10), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(0).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(9).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(9).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Get(15).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(15).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(21).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(21).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(30).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(30).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get(31).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe BehindFetchNext(20, 30)
        KeyMatcher.Get(31).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd

        //GROUP WHEN THERE MAX KEY IS A RANGE (EXCLUSIVE)
        KeyMatcher.Get(-1).apply(previous = 0, next = (5, (10, 20)), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = 0, next = (5, (10, 20)), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, (10, 20)), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(-1).apply(previous = (5, (10, 20)), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(0).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, (10, 20)), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(0).apply(previous = (5, (10, 20)), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(5).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(5).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(6).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(6).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(9).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(9).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(10).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(10).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(15).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(15).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Get(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Get(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(31).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(31).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe BehindFetchNext((20, (30, 40)))
        KeyMatcher.Get(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe AheadOrNoneOrEnd
      }

      "up to the largest key and exit iteration early if the next key is larger then the key to find" in {

        def find(toFind: Int) =
          (1 to 100).foldLeft(0) {
            case (iterationCount, next) =>
              val result = KeyMatcher.Get(toFind).apply(previous = next, next = Some(next + 1), hasMore = true)
              if (next + 1 == toFind) {
                result shouldBe Matched(next, toFind, None)
                iterationCount + 1
              } else if (next + 1 > toFind) {
                result shouldBe AheadOrNoneOrEnd
                iterationCount
              } else {
                result shouldBe BehindFetchNext(next + 1)
                iterationCount + 1
              }
          } shouldBe (toFind - 1)

        (1 to 100) foreach find
      }
    }

    "Get.SeekOne" when {
      "Fixed" in {
        //-1
        //   0, 1, 2
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        //0
        //0, 1, 2
        KeyMatcher.Get.SeekOne(0).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Get.SeekOne(0).apply(previous = 0, next = None, hasMore = true) shouldBe Matched(None, 0, None)
        KeyMatcher.Get.SeekOne(0).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        //next should never be fetched if previous was a match. This should not occur in actual scenarios.
        //        KeyMatcher.Get.NextOnly(0).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0)
        //        KeyMatcher.Get.NextOnly(0).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(0)
        //        KeyMatcher.Get.NextOnly(0).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0)
        //        KeyMatcher.Get.NextOnly(0).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(0)

        //   1
        //0, 1, 2
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Get.SeekOne(1).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Get.SeekOne(1).apply(previous = 1, next = None, hasMore = true) shouldBe Matched(None, 1, None)
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(0, 1, None)
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        //      2
        //0, 1, 2
        KeyMatcher.Get.SeekOne(2).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(2).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Get.SeekOne(2).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(2).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindStopped(1)
        KeyMatcher.Get.SeekOne(2).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(2).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Get.SeekOne(2).apply(previous = 1, next = 2, hasMore = false) shouldBe Matched(1, 2, None)
        KeyMatcher.Get.SeekOne(2).apply(previous = 1, next = 2, hasMore = true) shouldBe Matched(1, 2, None)
        KeyMatcher.Get.SeekOne(2).apply(previous = 2, next = None, hasMore = false) shouldBe Matched(None, 2, None)
        KeyMatcher.Get.SeekOne(2).apply(previous = 2, next = None, hasMore = true) shouldBe Matched(None, 2, None)

        //         3
        //0, 1, 2
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = None, hasMore = true) shouldBe BehindFetchNext(2)
        KeyMatcher.Get.SeekOne(3).apply(previous = 3, next = None, hasMore = false) shouldBe Matched(None, 3, None)
        KeyMatcher.Get.SeekOne(3).apply(previous = 3, next = None, hasMore = true) shouldBe Matched(None, 3, None)
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = 3, hasMore = false) shouldBe Matched(2, 3, None)
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = 3, hasMore = true) shouldBe Matched(2, 3, None)
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = 4, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(3).apply(previous = 2, next = 4, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(3).apply(previous = 0, next = 4, hasMore = true) shouldBe AheadOrNoneOrEnd
      }

      //range tests
      "Range" in {
        import RangeImplicits._

        //-1
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, 10), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, 10), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(21).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd
      }

      //group tests
      "Group" in {
        import GroupImplicits._

        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, 10), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, 10), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, 10), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Get.SeekOne(15).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(30).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(30).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe Matched((5, 10), (20, 30), None)
        KeyMatcher.Get.SeekOne(31).apply(previous = (5, 10), next = (20, 30), hasMore = true) shouldBe BehindStopped(20, 30)
        KeyMatcher.Get.SeekOne(31).apply(previous = (5, 10), next = (20, 30), hasMore = false) shouldBe AheadOrNoneOrEnd

        //GROUP WHEN THERE MAX KEY IS A RANGE (EXCLUSIVE)
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, (10, 20)), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = 0, next = (5, (10, 20)), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, (10, 20)), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(-1).apply(previous = (5, (10, 20)), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(0).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, (10, 20)), next = 20, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(0).apply(previous = (5, (10, 20)), next = 20, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(5).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(5).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(6).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(9).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(10).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Get.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext((5, (10, 20)))
        KeyMatcher.Get.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext((5, (10, 20)))
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Get.SeekOne(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(31).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(31).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Get.SeekOne(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe BehindStopped((20, (30, 40)))
        KeyMatcher.Get.SeekOne(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe AheadOrNoneOrEnd
      }

      "up to the largest key and exit iteration early if the next key is larger then the key to find" in {

        def find(toFind: Int) =
          (1 to 100).foldLeft(0) {
            case (iterationCount, next) =>
              val result = KeyMatcher.Get(toFind).apply(previous = next, next = Some(next + 1), hasMore = true)
              if (next + 1 == toFind) {
                result shouldBe Matched(next, toFind, None)
                iterationCount + 1
              } else if (next + 1 > toFind) {
                result shouldBe AheadOrNoneOrEnd
                iterationCount
              } else {
                result shouldBe BehindFetchNext(next + 1)
                iterationCount + 1
              }
          } shouldBe (toFind - 1)

        (1 to 100) foreach find
      }
    }

    "Lower" when {
      "Fixed" in {
        //0, 1, 2
        KeyMatcher.Lower(-1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(-1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower(0).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(0).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower(1).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower(1).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower(1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(1).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(None, 0, 1)
        KeyMatcher.Lower(1).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(None, 0, 1)
        KeyMatcher.Lower(1).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(None, 0, 2)
        KeyMatcher.Lower(1).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(None, 0, 2)

        KeyMatcher.Lower(2).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower(2).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower(2).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Lower(2).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower(2).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Lower(2).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower(2).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(None, 0, 2)
        KeyMatcher.Lower(2).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(None, 0, 2)

        KeyMatcher.Lower(3).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower(3).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower(3).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Lower(3).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower(3).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Lower(3).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower(3).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0, 2, None)
        KeyMatcher.Lower(3).apply(previous = 0, next = 2, hasMore = true) shouldBe BehindFetchNext(2)
      }

      "Range" in {

        import RangeImplicits._

        KeyMatcher.Lower(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Lower(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Lower(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))

        KeyMatcher.Lower(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Lower(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)
      }

      "Group" in {
        import GroupImplicits._

        KeyMatcher.Lower(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Lower(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Lower(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))

        KeyMatcher.Lower(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Lower(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)

        //Group when maxKey is range
        KeyMatcher.Lower(3).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(3).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(4).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower(5).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower(6).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(6).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(10).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(10).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(11).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(11).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(19).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(19).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))

        KeyMatcher.Lower(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))

        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))

        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower(30).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(30).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower(40).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(40).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower(41).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower(41).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower(41).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe BehindFetchNext(20, (30, 40))
        KeyMatcher.Lower(41).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
      }

      "minimum number of lower keys to fulfil the request" in {

        def find(toFind: Int) =
          (1 to 100).foldLeft(0) {
            case (iterationCount, next) =>
              val result = KeyMatcher.Lower(toFind).apply(previous = next, next = Some(next + 1), hasMore = true)
              if (next + 1 == toFind) {
                result shouldBe Matched(None, toFind - 1, toFind)
                iterationCount + 1
              } else if (next + 1 > toFind) {
                result shouldBe AheadOrNoneOrEnd
                iterationCount
              } else {
                result shouldBe BehindFetchNext(next + 1)
                iterationCount + 1
              }
          } shouldBe (toFind - 1)

        (1 to 100) foreach find
      }
    }

    "Lower.SeekOne" when {
      "Fixed" in {
        //0, 1, 2
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(-1).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = 1, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(0).apply(previous = 0, next = 2, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower.SeekOne(1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(1).apply(previous = 1, next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(None, 0, 1)
        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(None, 0, 1)
        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(None, 0, 2)
        KeyMatcher.Lower.SeekOne(1).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(None, 0, 2)

        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindStopped(1)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(None, 0, 2)
        KeyMatcher.Lower.SeekOne(2).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(None, 0, 2)

        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindStopped(1)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0, 2, None)
        KeyMatcher.Lower.SeekOne(3).apply(previous = 0, next = 2, hasMore = true) shouldBe BehindStopped(2)
      }

      "Range" in {

        import RangeImplicits._

        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))

        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindStopped(15, 20)
      }

      "Group" in {
        import GroupImplicits._

        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched(None, (5, 10), (15, 20))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched(None, (5, 10), (15, 20))

        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindStopped(15, 20)

        //Group when maxKey is range
        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(3).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(4).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Lower.SeekOne(5).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(6).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(10).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(11).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(19).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(19).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext((5, (10, 20)))

        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower.SeekOne(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower.SeekOne(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))

        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))
        KeyMatcher.Lower.SeekOne(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched(None, (5, (10, 20)), (20, (30, 40)))

        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext((5, (10, 20)))
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower.SeekOne(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower.SeekOne(30).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(30).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext((5, (10, 20)))
        KeyMatcher.Lower.SeekOne(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower.SeekOne(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower.SeekOne(40).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(40).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower.SeekOne(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Lower.SeekOne(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Lower.SeekOne(41).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Lower.SeekOne(41).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Lower.SeekOne(41).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe BehindStopped(20, (30, 40))
        KeyMatcher.Lower.SeekOne(41).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
      }
    }

    "Higher" when {

      "Fixed" in {
        //0, 1, 2
        KeyMatcher.Higher(-1).apply(previous = 0, next = None, hasMore = false) shouldBe Matched(None, 0, None)
        KeyMatcher.Higher(-1).apply(previous = 0, next = None, hasMore = true) shouldBe Matched(None, 0, None)
        KeyMatcher.Higher(-1).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Higher(-1).apply(previous = 1, next = None, hasMore = true) shouldBe Matched(None, 1, None)
        //    KeyMatcher.Higher(-1).apply[CreatedReadOnly](previous = 0, next = 1, hasMore = false) shouldBe AheadOrEnd
        //    KeyMatcher.Higher(-1).apply[CreatedReadOnly](previous = 0, next = 1, hasMore = true) shouldBe AheadOrEnd
        //    KeyMatcher.Higher(-1).apply[CreatedReadOnly](previous = 0, next = 2, hasMore = false) shouldBe AheadOrEnd
        //    KeyMatcher.Higher(-1).apply[CreatedReadOnly](previous = 0, next = 2, hasMore = true) shouldBe AheadOrEnd

        KeyMatcher.Higher(0).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(0).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Higher(0).apply(previous = 1, next = None, hasMore = false) shouldBe Matched(None, 1, None)
        KeyMatcher.Higher(0).apply(previous = 1, next = None, hasMore = true) shouldBe Matched(None, 1, None)
        KeyMatcher.Higher(0).apply(previous = 0, next = 1, hasMore = false) shouldBe Matched(0, 1, None)
        KeyMatcher.Higher(0).apply(previous = 0, next = 1, hasMore = true) shouldBe Matched(0, 1, None)
        KeyMatcher.Higher(0).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0, 2, None)
        KeyMatcher.Higher(0).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(0, 2, None)

        KeyMatcher.Higher(1).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(1).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Higher(1).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(1).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(1).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(1).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(1).apply(previous = 0, next = 2, hasMore = false) shouldBe Matched(0, 2, None)
        KeyMatcher.Higher(1).apply(previous = 0, next = 2, hasMore = true) shouldBe Matched(0, 2, None)

        KeyMatcher.Higher(2).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(2).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Higher(2).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(2).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(2).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(2).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(2).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(2).apply(previous = 0, next = 2, hasMore = true) shouldBe BehindFetchNext(2)

        KeyMatcher.Higher(3).apply(previous = 0, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(3).apply(previous = 0, next = None, hasMore = true) shouldBe BehindFetchNext(0)
        KeyMatcher.Higher(3).apply(previous = 1, next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(3).apply(previous = 1, next = None, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(3).apply(previous = 0, next = 1, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(3).apply(previous = 0, next = 1, hasMore = true) shouldBe BehindFetchNext(1)
        KeyMatcher.Higher(3).apply(previous = 0, next = 2, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(3).apply(previous = 0, next = 2, hasMore = true) shouldBe BehindFetchNext(2)
      }

      "Range" in {
        import RangeImplicits._

        KeyMatcher.Higher(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Higher(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Higher(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Higher(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Higher(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Higher(19).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(19).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Higher(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)

        KeyMatcher.Higher(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)
      }

      "Group" in {
        import GroupImplicits._

        KeyMatcher.Higher(3).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(3).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(4).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(4).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(5).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(5).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)

        KeyMatcher.Higher(6).apply(previous = (5, 10), next = None, hasMore = false) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(6).apply(previous = (5, 10), next = None, hasMore = true) shouldBe Matched(None, (5, 10), None)
        KeyMatcher.Higher(10).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Higher(10).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd

        KeyMatcher.Higher(11).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(11).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)

        KeyMatcher.Higher(12).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(12).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(15).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(15).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Higher(19).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe Matched((5, 10), (15, 20), None)
        KeyMatcher.Higher(19).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe Matched((5, 10), (15, 20), None)

        KeyMatcher.Higher(20).apply(previous = (5, 10), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = None, hasMore = true) shouldBe BehindFetchNext(5, 10)
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)

        KeyMatcher.Higher(21).apply(previous = (5, 10), next = (15, 20), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(21).apply(previous = (5, 10), next = (15, 20), hasMore = true) shouldBe BehindFetchNext(15, 20)

        //When max key is Range
        KeyMatcher.Higher(3).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(3).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(4).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(4).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(5).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(5).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)

        KeyMatcher.Higher(6).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(6).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(10).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(10).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(11).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(11).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)

        KeyMatcher.Higher(12).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(12).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe Matched(None, (5, (10, 20)), None)
        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))

        KeyMatcher.Higher(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(12).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(15).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Higher(19).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(19).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = None, hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = None, hasMore = true) shouldBe BehindFetchNext(5, (10, 20))
        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(20).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Higher(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(21).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Higher(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(30).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(39).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)
        KeyMatcher.Higher(39).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe Matched((5, (10, 20)), (20, (30, 40)), None)

        KeyMatcher.Higher(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = false) shouldBe AheadOrNoneOrEnd
        KeyMatcher.Higher(40).apply(previous = (5, (10, 20)), next = (20, (30, 40)), hasMore = true) shouldBe BehindFetchNext(20, (30, 40))
      }
    }
  }
}
