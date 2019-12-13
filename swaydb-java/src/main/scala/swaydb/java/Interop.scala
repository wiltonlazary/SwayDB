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

package swaydb.java

import swaydb.Apply
import swaydb.IO.ThrowableIO

import scala.reflect.ClassTag

object Interop {

  /**
   * Experimental function that Converts a Scala [[swaydb.Map]] into [[swaydb.java.MapIO]].
   *
   * When working with Java and Scala both in the same application invoke [[swaydb.java.MapIO.asScala]] to access the
   * map is Scala. Converting from Scala to Java is not recommended since Java implementation is
   * dependant on Scala implementation and not the other way around (One way - Java -> Scala).
   */
  private class InteropImplicit[K, V, F, T[_]](map: swaydb.Map[K, V, F, T]) {
    def asJava(implicit tag: ClassTag[F]): MapIO[K, V, swaydb.java.PureFunction[K, V, Return.Map[V]]] = {
      val scalaMap: swaydb.Map[K, V, F, ThrowableIO] = map.toTag[swaydb.IO.ThrowableIO]
      if (tag == ClassTag.Nothing)
        MapIO[K, V, swaydb.java.PureFunction.VoidM[K, V]](scalaMap).asInstanceOf[MapIO[K, V, swaydb.java.PureFunction[K, V, Return.Map[V]]]]
      else
        MapIO[K, V, swaydb.java.PureFunction[K, V, Return.Map[V]]](scalaMap)
    }
  }

  /**
   * Converts a java Map function to Scala.
   */
  implicit class MapInterop[K, V, R <: Return.Map[V]](function: PureFunction[K, V, R]) {
    def asScala: swaydb.PureFunction[K, V, Apply.Map[V]] =
      PureFunction asScala function
  }

  /**
   * Converts java Set function to Scala.
   */
  implicit class SetInterop[K, R <: Return.Set[Void]](function: PureFunction.OnKey[K, Void, R]) {
    def asScala: swaydb.PureFunction.OnKey[K, Nothing, Apply.Set[Nothing]] =
      PureFunction asScala function
  }
}