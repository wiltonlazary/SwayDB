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

package swaydb

import scala.collection.mutable

/**
 * [[scala.collection.mutable.Builder]] requires two implementations for 2.13 and 2.12.
 *
 * 2.13 requires addOne and 2.12 requires +=. So this type simply wrapper around
 * Builder which is used internally to avoid having 2 implementation of Builder.
 */
protected trait Aggregator[-A, +T] extends ForEach[A] {
  def add(item: A): Unit

  def result: T

  final override def apply(item: A): Unit =
    add(item)
}

protected object Aggregator {
  def fromBuilder[A, T](builder: mutable.Builder[A, T]): Aggregator[A, T] =
    new Aggregator[A, T] {
      override def add(item: A): Unit =
        builder += item

      override def result: T =
        builder.result()
    }
}
