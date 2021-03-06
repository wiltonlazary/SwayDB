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

package swaydb.persistent

import swaydb.SwayDB
import swaydb.data.accelerate.LevelZeroMeter
import swaydb.data.compaction.{LevelMeter, Throttle}
import swaydb.data.config.MemoryCache.ByteCacheOnly
import swaydb.data.config._
import swaydb.data.util.StorageUnits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object DefaultConfigs {

  implicit lazy val sweeperEC = SwayDB.sweeperExecutionContext

  def sortedKeyIndex(cacheOnAccess: Boolean = true): SortedKeyIndex.Enable =
    SortedKeyIndex.Enable(
      prefixCompression = PrefixCompression.Disable(normaliseIndexForBinarySearch = false),
      enablePositionIndex = true,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compressions = _ => Seq.empty
    )

  def randomKeyIndex(cacheOnAccess: Boolean = true): RandomKeyIndex.Enable =
    RandomKeyIndex.Enable(
      maxProbe = 1,
      minimumNumberOfKeys = 5,
      minimumNumberOfHits = 2,
      indexFormat = IndexFormat.Reference,
      allocateSpace = _.requiredSpace,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compression = _ => Seq.empty
    )

  def binarySearchIndex(cacheOnAccess: Boolean = true): BinarySearchIndex.FullIndex =
    BinarySearchIndex.FullIndex(
      minimumNumberOfKeys = 10,
      searchSortedIndexDirectly = true,
      indexFormat = IndexFormat.CopyKey,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compression = _ => Seq.empty
    )

  def mightContainKeyIndex(cacheOnAccess: Boolean = true): MightContainIndex.Enable =
    MightContainIndex.Enable(
      falsePositiveRate = 0.01,
      minimumNumberOfKeys = 10,
      updateMaxProbe = optimalMaxProbe => 1,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compression = _ => Seq.empty
    )

  def valuesConfig(cacheOnAccess: Boolean = false): ValuesConfig =
    ValuesConfig(
      compressDuplicateValues = true,
      compressDuplicateRangeValues = true,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compression = _ => Seq.empty
    )

  def segmentConfig(cacheOnAccess: Boolean = false): SegmentConfig =
    SegmentConfig(
      cacheSegmentBlocksOnCreate = true,
      deleteSegmentsEventually = true,
      pushForward = true,
      mmap = MMAP.WriteAndRead,
      minSegmentSize = 2.mb,
      maxKeyValuesPerSegmentGroup = Int.MaxValue,
      ioStrategy = {
        case IOAction.OpenResource => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case IOAction.ReadDataOverview => IOStrategy.SynchronisedIO(cacheOnAccess = true)
        case action: IOAction.DataAction => IOStrategy.SynchronisedIO(cacheOnAccess = action.isCompressed || cacheOnAccess)
      },
      compression = _ => Seq.empty
    )

  def fileCache(implicit ec: ExecutionContext = sweeperEC): FileCache.Enable =
    FileCache.Enable(
      maxOpen = 500,
      actorConfig =
        ActorConfig.TimeLoop(
          delay = 10.seconds,
          ec = ec
        )
    )

  def memoryCache(implicit ec: ExecutionContext = sweeperEC): MemoryCache.ByteCacheOnly =
    ByteCacheOnly(
      minIOSeekSize = 4098,
      skipBlockCacheSeekSize = 4098 * 10,
      cacheCapacity = 1.gb,
      actorConfig =
        ActorConfig.TimeLoop(
          delay = 10.seconds,
          ec = ec
        )
    )

  def levelZeroThrottle(meter: LevelZeroMeter): FiniteDuration = {
    val mapsCount = meter.mapsCount
    if (mapsCount > 1)
      Duration.Zero
    else
      1.day
  }

  def levelOneThrottle(meter: LevelMeter): Throttle = {
    val delay = (5 - meter.segmentsCount).seconds
    val batch = meter.segmentsCount min 5
    Throttle(delay, batch)
  }

  def levelTwoThrottle(meter: LevelMeter): Throttle = {
    val delay = (10 - meter.segmentsCount).seconds
    val batch = meter.segmentsCount min 5
    Throttle(delay, batch)
  }

  def levelThreeThrottle(meter: LevelMeter): Throttle = {
    val delay = (30 - meter.segmentsCount).seconds
    val batch = meter.segmentsCount min 5
    Throttle(delay, batch)
  }

  def levelFourThrottle(meter: LevelMeter): Throttle = {
    val delay = (40 - meter.segmentsCount).seconds
    val batch = meter.segmentsCount min 5
    Throttle(delay, batch)
  }

  def levelFiveThrottle(meter: LevelMeter): Throttle = {
    val delay = (50 - meter.segmentsCount).seconds
    val batch = meter.segmentsCount min 5
    Throttle(delay, batch)
  }

  def levelSixThrottle(meter: LevelMeter): Throttle =
    if (meter.requiresCleanUp)
      Throttle(10.seconds, 2)
    else
      Throttle(1.hour, 5)
}
