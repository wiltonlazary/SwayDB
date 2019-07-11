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

package swaydb.core.group.compression

import swaydb.compression.CompressionInternal
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data._
import swaydb.core.segment.format.a.block._
import swaydb.core.{TestBase, TestLimitQueues, TestTimer}
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

/**
  * [[swaydb.core.group.compression.GroupCompressor]] is always invoked directly from [[Transient.Group]] there these test cases initialise the Group
  * to find full code coverage.
  *
  */
class GroupCompressorSpec extends TestBase {

  implicit val keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default
  implicit def testTimer: TestTimer = TestTimer.random
  implicit val limiter = TestLimitQueues.keyValueLimiter

  val keyValueCount = 100

  def genKeyValuesWithCompression(compressions: Seq[CompressionInternal]) =
    eitherOne(
      //either one key-value
      left =
        eitherOne(
          Slice(randomFixedKeyValue(1, eitherOne(None, Some(2)))),
          Slice(randomRangeKeyValue(1, 2, randomFromValueOption(), rangeValue = Value.update(2, randomDeadlineOption)))
        ).toTransient,
      right =
        //multiple key-values
        randomizedKeyValues(keyValueCount, startId = Some(1), addPut = true)
    ).updateStats(
      valuesConfig = Values.Config.random.copy(compressions = compressions),
      sortedIndexConfig = SortedIndex.Config.random.copy(compressions = compressions),
      binarySearchIndexConfig = BinarySearchIndex.Config.random.copy(compressions = compressions),
      hashIndexConfig = HashIndex.Config.random.copy(compressions = compressions),
      bloomFilterConfig = BloomFilter.Config.random.copy(compressions = compressions)
    )

  "GroupCompressor" should {
    "return no Group if key-values are empty" in {
      Transient.Group(
        keyValues = Slice.empty,
        previous = None,
        groupCompressions = randomCompressionsOrEmpty(),
        valuesConfig = Values.Config.random,
        sortedIndexConfig = SortedIndex.Config.random,
        binarySearchIndexConfig = BinarySearchIndex.Config.random,
        hashIndexConfig = HashIndex.Config.random,
        bloomFilterConfig = BloomFilter.Config.random
      ).failed.get.exception.getMessage shouldBe GroupCompressor.cannotGroupEmptyValues.exception.getMessage
    }

    "create a group" when {
      "key-values are un-compressible" in {
        runThis(100.times) {
          val compressions = randomCompressionsLZ4OrSnappy(Int.MaxValue)

          val keyValues = genKeyValuesWithCompression(compressions)

          val group =
            Transient.Group(
              keyValues = keyValues,
              previous = None,
              groupCompressions = randomCompressionsOrEmpty(),
              valuesConfig = Values.Config.random,
              sortedIndexConfig = SortedIndex.Config.random,
              binarySearchIndexConfig = BinarySearchIndex.Config.random,
              hashIndexConfig = HashIndex.Config.random,
              bloomFilterConfig = BloomFilter.Config.random
            ).get

          //none of the group's blocks are compressed.
          val persistedGroup = assertGroup(group)
//          persistedGroup.segment.blockCache.createSortedIndexReader().get.block.compressionInfo shouldBe empty
//          persistedGroup.segment.blockCache.createBinarySearchReader().get foreach (_.block.compressionInfo shouldBe empty)
//          persistedGroup.segment.blockCache.createHashIndexReader().get foreach (_.block.compressionInfo shouldBe empty)
//          persistedGroup.segment.blockCache.createBloomFilterReader().get foreach (_.block.compressionInfo shouldBe empty)
//          persistedGroup.segment.blockCache.createValuesReader().get foreach (_.block.compressionInfo shouldBe empty)
          ???
        }
      }

      "key-values are compressible" in {
        runThis(100.times) {

          val compressions = randomCompressionsLZ4OrSnappy(Int.MinValue)

          val keyValues = genKeyValuesWithCompression(compressions)

          val group =
            Transient.Group(
              keyValues = keyValues,
              previous = None,
              groupCompressions = randomCompressionsOrEmpty(),
              valuesConfig = Values.Config.random,
              sortedIndexConfig = SortedIndex.Config.random,
              binarySearchIndexConfig = BinarySearchIndex.Config.random,
              hashIndexConfig = HashIndex.Config.random,
              bloomFilterConfig = BloomFilter.Config.random
            ).get

          //none of the group's blocks are compressed.
          val persistedGroup = assertGroup(group)
//          persistedGroup.segment.blockCache.createSortedIndexReader().get.block.compressionInfo shouldBe defined
//          persistedGroup.segment.blockCache.createBinarySearchReader().get foreach (_.block.compressionInfo shouldBe defined)
//          persistedGroup.segment.blockCache.createHashIndexReader().get foreach (_.block.compressionInfo shouldBe defined)
//          persistedGroup.segment.blockCache.createBloomFilterReader().get foreach (_.block.compressionInfo shouldBe defined)
//          persistedGroup.segment.blockCache.createValuesReader().get foreach (_.block.compressionInfo shouldBe defined)
          ???
        }
      }
    }
  }
}
