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

package swaydb.core.actor

import java.util.concurrent.ConcurrentLinkedQueue

import org.scalatest.{Matchers, WordSpec}
import swaydb.core.TestExecutionContext
import swaydb.core.util.Benchmark
import swaydb.data.config.ActorConfig.QueueOrder
import swaydb.{Actor, ActorRef, Scheduler}

import scala.collection.parallel.CollectionConverters._
import scala.concurrent.duration._

class ActorPerformanceSpec extends WordSpec with Matchers {

  implicit val ec = TestExecutionContext.executionContext
  implicit val ordering = QueueOrder.FIFO
  implicit val scheduler = Scheduler()

  "performance test" in {
    //0.251675378 seconds.
//    val actor =
//      Actor.timerLoopCache[Int](100000, _ => 1, 5.second) {
//        (_: Int, self: ActorRef[Int, Unit]) =>
//      }

        val actor =
          Actor.timerCache[Int](100000, _ => 1, 5.second) {
            (_: Int, self: ActorRef[Int, Unit]) =>
          }


    //0.111304334 seconds.
    //    val actor =
    //      Actor.cache[Int](100000, _ => 1) {
    //        (_: Int, self: ActorRef[Int, Unit]) =>
    //      }

    //0.186314412 seconds.
    //    val actor =
    //      Actor[Int] {
    //        (_: Int, self: ActorRef[Int, Unit]) =>
    //      }

    val queue = new ConcurrentLinkedQueue[Int]()

    Benchmark("") {
      (1 to 1000000).par foreach {
        i =>
          actor send i
        //          queue.add(i)
      }
    }
  }
}
