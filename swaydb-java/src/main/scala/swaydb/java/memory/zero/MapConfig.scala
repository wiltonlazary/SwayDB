///*
// * Copyright (c) 2020 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
// *
// * This file is a part of SwayDB.
// *
// * SwayDB is free software: you can redistribute it and/or modify
// * it under the terms of the GNU Affero General Public License as
// * published by the Free Software Foundation, either version 3 of the
// * License, or (at your option) any later version.
// *
// * SwayDB is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// * GNU Affero General Public License for more details.
// *
// * You should have received a copy of the GNU Affero General Public License
// * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
// */
//
//package swaydb.java.memory.zero
//
//import java.util.Comparator
//
//import swaydb.data.accelerate.{Accelerator, LevelZeroMeter}
//import swaydb.data.order.KeyOrder
//import swaydb.data.slice.Slice
//import swaydb.data.util.StorageUnits._
//import swaydb.java.data.slice.ByteSlice
//import swaydb.java.data.util.Java.JavaFunction
//import swaydb.java.serializers.{SerializerConverter, Serializer => JavaSerializer}
//import swaydb.java.{IO, KeyOrderConverter, Return}
//import swaydb.serializers.Serializer
//import swaydb.{Apply, Bag}
//
//import scala.beans.BeanProperty
//import scala.compat.java8.FunctionConverters._
//import scala.reflect.ClassTag
//
//object MapConfig {
//
//  class Config[K, V, F <: swaydb.java.PureFunction[K, V, Return.Map[V]], SF](@BeanProperty var mapSize: Int = 4.mb,
//                                                                             @BeanProperty var acceleration: JavaFunction[LevelZeroMeter, Accelerator] = (Accelerator.noBrakes() _).asJava,
//                                                                             @BeanProperty var comparator: IO[KeyComparator[ByteSlice], KeyComparator[K]] = IO.leftNeverException[KeyComparator[ByteSlice], KeyComparator[K]](swaydb.java.SwayDB.defaultComparator),
//                                                                             keySerializer: Serializer[K],
//                                                                             valueSerializer: Serializer[V],
//                                                                             functionClassTag: ClassTag[SF]) {
//
//    implicit def scalaKeyOrder: KeyOrder[Slice[Byte]] = KeyOrderConverter.toScalaKeyOrder(comparator, keySerializer)
//
//    def init(): swaydb.java.Map[K, V, F] = {
//      val scalaMap =
//        swaydb.memory.zero.Map[K, V, SF, Bag.Less](
//          mapSize = mapSize,
//          acceleration = acceleration.asScala
//        )(keySerializer = keySerializer,
//          valueSerializer = valueSerializer,
//          functionClassTag = functionClassTag,
//          bag = Bag.less,
//          keyOrder = Left(scalaKeyOrder)
//        ).get
//
//      swaydb.java.Map[K, V, F](scalaMap)
//    }
//  }
//
//  def withFunctions[K, V](keySerializer: JavaSerializer[K],
//                          valueSerializer: JavaSerializer[V]): Config[K, V, swaydb.java.PureFunction.OnKey[K, V, Return.Map[V]], swaydb.PureFunction.OnKey[K, V, Apply.Map[V]]] =
//    new Config(
//      keySerializer = SerializerConverter.toScala(keySerializer),
//      valueSerializer = SerializerConverter.toScala(valueSerializer),
//      functionClassTag = ClassTag.Any.asInstanceOf[ClassTag[swaydb.PureFunction.OnKey[K, V, Apply.Map[V]]]]
//    )
//
//  def withoutFunctions[K, V](keySerializer: JavaSerializer[K],
//                             valueSerializer: JavaSerializer[V]): Config[K, V, swaydb.java.PureFunction.VoidM[K, V], Void] =
//    new Config[K, V, swaydb.java.PureFunction.VoidM[K, V], Void](
//      keySerializer = SerializerConverter.toScala(keySerializer),
//      valueSerializer = SerializerConverter.toScala(valueSerializer),
//      functionClassTag = ClassTag.Nothing.asInstanceOf[ClassTag[Void]]
//    )
//}
