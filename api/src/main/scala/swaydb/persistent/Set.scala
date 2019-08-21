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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.persistent

import java.nio.file.Path

import com.typesafe.scalalogging.LazyLogging
import swaydb.configs.level.{DefaultGroupBy, DefaultPersistentConfig}
import swaydb.core.BlockingCore
import swaydb.core.function.FunctionStore
import swaydb.data.accelerate.{Accelerator, LevelZeroMeter}
import swaydb.data.api.grouping.GroupBy
import swaydb.data.config._
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice
import swaydb.data.util.StorageUnits._
import swaydb.serializers.Serializer
import swaydb.{Error, IO, SwayDB}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, _}

object Set extends LazyLogging {

  implicit val timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long
  implicit val functionStore: FunctionStore = FunctionStore.memory()

  /**
   * For custom configurations read documentation on website: http://www.swaydb.io/configuring-levels
   */
  def apply[T](dir: Path,
               maxOpenSegments: Int = 1000,
               cacheSize: Int = 100.mb,
               mapSize: Int = 4.mb,
               mmapMaps: Boolean = true,
               recoveryMode: RecoveryMode = RecoveryMode.ReportFailure,
               mmapAppendix: Boolean = true,
               mmapSegments: MMAP = MMAP.WriteAndRead,
               segmentSize: Int = 2.mb,
               appendixFlushCheckpointSize: Int = 2.mb,
               otherDirs: Seq[Dir] = Seq.empty,
               cacheCheckDelay: FiniteDuration = 10.seconds,
               segmentsOpenCheckDelay: FiniteDuration = 10.seconds,
               mightContainFalsePositiveRate: Double = 0.01,
               blockCacheSize: Option[Int] = Some(4098),
               compressDuplicateValues: Boolean = true,
               deleteSegmentsEventually: Boolean = false,
               lastLevelGroupBy: Option[GroupBy.KeyValues] = Some(DefaultGroupBy()),
               acceleration: LevelZeroMeter => Accelerator = Accelerator.noBrakes())(implicit serializer: Serializer[T],
                                                                                     keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                     fileSweeperEC: ExecutionContext = SwayDB.defaultExecutionContext,
                                                                                     cacheLimiterEC: ExecutionContext = SwayDB.defaultExecutionContext): IO[Error.Boot, swaydb.Set[T, IO.ApiIO]] =
    BlockingCore(
      config = DefaultPersistentConfig(
        dir = dir,
        otherDirs = otherDirs,
        recoveryMode = recoveryMode,
        mapSize = mapSize,
        mmapMaps = mmapMaps,
        mmapSegments = mmapSegments,
        mmapAppendix = mmapAppendix,
        segmentSize = segmentSize,
        compressDuplicateValues = compressDuplicateValues,
        deleteSegmentsEventually = deleteSegmentsEventually,
        appendixFlushCheckpointSize = appendixFlushCheckpointSize,
        mightContainFalsePositiveRate = mightContainFalsePositiveRate,
        groupBy = lastLevelGroupBy,
        acceleration = acceleration
      ),
      maxOpenSegments = maxOpenSegments,
      keyValueCacheSize = Some(cacheSize),
      keyValueCacheCheckDelay = cacheCheckDelay,
      blockCacheSize = blockCacheSize,
      segmentsOpenCheckDelay = segmentsOpenCheckDelay,
      fileSweeperEC = fileSweeperEC,
      cacheLimiterEC = cacheLimiterEC
    ) map {
      db =>
        swaydb.Set[T](db)
    }
}
