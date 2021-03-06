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

package swaydb.memory

import swaydb.SwayDB
import swaydb.data.accelerate.LevelZeroMeter
import swaydb.data.compaction.{LevelMeter, Throttle}
import swaydb.data.config.{ActorConfig, FileCache}

import scala.concurrent.duration._

object DefaultConfigs {

  def fileCache(maxOpen: Int = 100,
                actorConfig: ActorConfig = ActorConfig.TimeLoop(delay = 10.seconds, ec = SwayDB.sweeperExecutionContext)): FileCache.Enable =
    FileCache.Enable(
      maxOpen = maxOpen,
      actorConfig = actorConfig
    )

  def levelZeroThrottle(meter: LevelZeroMeter): FiniteDuration =
    swaydb.persistent.DefaultConfigs.levelZeroThrottle(meter)

  def lastLevelThrottle(meter: LevelMeter): Throttle =
    swaydb.persistent.DefaultConfigs.levelSixThrottle(meter)
}
