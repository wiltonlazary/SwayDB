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

import java.util.concurrent.atomic.AtomicLong

import swaydb.core.data.Time

private[core] object MemoryTimer {
  def apply(): MemoryTimer =
    new MemoryTimer(new AtomicLong(0))
}

private[core] class MemoryTimer(time: AtomicLong) extends Timer {

  override val empty = false

  def next: Time =
    Time(time.incrementAndGet())

  override def close: Unit =
    ()
}