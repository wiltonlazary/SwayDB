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

import org.scalatest.{Matchers, WordSpec}
import swaydb.core.RunThis._
import swaydb.core.TestBase
import swaydb.core.TestData._
import swaydb.{Actor, ActorWire}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

class ActorWireSpec extends WordSpec with Matchers with TestBase {

  "ActorWire" should {

    "process messages in order of arrival" in {
      class MyImpl(message: ListBuffer[Int]) {
        def message(int: Int): Unit =
          message += int

        def get(): Iterable[Int] = message
      }

      val actor = Actor.wire(new MyImpl(ListBuffer.empty))

      actor
        .ask
        .map {
          (impl, state) =>
            (1 to 100) foreach {
              i =>
                impl.message(i)
            }
        }.await(2.seconds)

      actor
        .ask
        .map {
          (impl, _) =>
            impl.get()
        }.await.toList should contain theSameElementsInOrderAs (1 to 100)
    }

    "ask" in {
      object MyImpl {
        def hello(name: String, replyTo: ActorWire[MyImpl.type, Unit]): String =
          s"Hello $name"
      }

      Actor.wire(MyImpl)
        .ask
        .map {
          (impl, state, self) =>
            impl.hello("John", self)
        }
        .await shouldBe "Hello John"
    }

    "askFlatMap" in {
      object MyImpl {
        def hello(name: String, replyTo: ActorWire[MyImpl.type, Unit]): Future[String] =
          Future(s"Hello $name")
      }

      Actor.wire(MyImpl)
        .ask
        .flatMap {
          (impl, _, self) =>
            impl.hello("John", self)
        }
        .await shouldBe "Hello John"
    }

    "send" in {
      class MyImpl(var name: String) {
        def hello(name: String): Future[String] =
          Future {
            this.name = name
            s"Hello $name"
          }

        def getName(): Future[String] =
          Future(name)
      }

      val actor = Actor.wire(new MyImpl(""))

      actor.send {
        (impl, state) =>
          impl.hello("John")
      }

      eventually {
        actor
          .ask
          .flatMap {
            (impl, state) =>
              impl.getName()
          }
          .await shouldBe "John"
      }
    }

    "scheduleAsk" in {
      class MyImpl(var invoked: Boolean = false) {
        def invoke(): Unit =
          invoked = true

        def getInvoked(): Boolean =
          invoked
      }

      val actor = Actor.wire(new MyImpl(invoked = false))

      actor.send(2.second) {
        (impl, _) =>
          impl.invoke()
      }

      def assert(expected: Boolean) =
        actor
          .ask
          .map {
            (impl, _) =>
              impl.getInvoked()
          }
          .await shouldBe expected

      assert(expected = false)
      sleep(1.second)
      assert(expected = false)

      sleep(1.second)
      eventually {
        actor
          .ask
          .map {
            (impl, _) =>
              impl.getInvoked()
          }
          .await shouldBe true
      }
    }

    "scheduleAskFlatMap" in {

      class MyImpl(var invoked: Boolean = false) {
        def invoke(): Future[Boolean] =
          Future {
            invoked = true
            invoked
          }

        def getInvoked(): Boolean =
          invoked
      }

      val actor = Actor.wire(new MyImpl(invoked = false))

      val result =
        actor
          .ask
          .flatMap(2.second) {
            (impl, _, _) =>
              impl.invoke()
          }

      def assert(expected: Boolean) =
        actor
          .ask
          .map {
            (impl, _) =>
              impl.getInvoked()
          }
          .await shouldBe expected

      assert(expected = false)
      sleep(1.second)
      assert(expected = false)

      sleep(1.second)

      actor
        .ask
        .map {
          (impl, _) =>
            impl.getInvoked()
        }
        .await shouldBe true

      result.task.await shouldBe true
    }

    "scheduleAskWithSelf" in {
      class MyImpl(var invoked: Boolean = false) {
        def invoke(replyTo: ActorWire[MyImpl, Unit]): Future[Boolean] =
          replyTo
            .ask
            .map {
              (impl, _) =>
                impl.setInvoked()
            }
            .map {
              _ =>
                invoked
            }

        private def setInvoked() =
          invoked = true

        def getInvoked() =
          invoked
      }

      val actor = Actor.wire(new MyImpl())

      val result =
        actor
          .ask
          .flatMap(2.second) {
            (impl, state, self) =>
              impl.invoke(self)
          }

      def assert(expected: Boolean) =
        actor
          .ask
          .map {
            (impl, _) =>
              impl.getInvoked()
          }
          .await shouldBe expected

      assert(expected = false)
      sleep(1.second)
      assert(expected = false)

      sleep(1.second)
      eventually {
        actor
          .ask
          .map {
            (impl, _) =>
              impl.getInvoked()
          }
          .await shouldBe true
      }
      result.task.await shouldBe true
    }
  }
}