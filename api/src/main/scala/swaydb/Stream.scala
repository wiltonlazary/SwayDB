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

package swaydb

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import swaydb.Stream.StreamBuilder
import swaydb.Wrap._
import swaydb.data.IO

object Stream {

  def apply[T, W[_]](items: Iterable[T])(implicit wrap: Wrap[W]): Stream[T, W] =
    new Stream[T, W](0, None) {

      private val iterator = items.iterator

      private def step(): W[Option[T]] =
        if (iterator.hasNext)
          wrap(Some(iterator.next()))
        else
          wrap.none

      override def headOption(): W[Option[T]] = step()
      override def next(previous: T): W[Option[T]] = step()
    }

  class StreamBuilder[T, W[_]](implicit wrap: Wrap[W]) extends mutable.Builder[T, Stream[T, W]] {
    protected var items: ListBuffer[T] = ListBuffer.empty[T]

    override def +=(x: T): this.type = {
      items += x
      this
    }

    def asSeq: Seq[T] =
      items

    override def clear() =
      items.clear()

    override def result: Stream[T, W] =
      new Stream[T, W](0, None) {

        var index = 0

        def step(): W[Option[T]] =
          if (index < items.size)
            wrap(items(index)) map {
              item =>
                index += 1
                Some(item)
            }
          else
            wrap.none

        override def headOption: W[Option[T]] = step()
        override def next(previous: T): W[Option[T]] = step()
      }
  }

  implicit def canBuildFrom[T, W[_]](implicit wrap: Wrap[W]): CanBuildFrom[Stream[T, W], T, Stream[T, W]] =
    new CanBuildFrom[Stream[T, W], T, Stream[T, W]] {
      override def apply(from: Stream[T, W]) =
        new StreamBuilder()

      override def apply(): mutable.Builder[T, Stream[T, W]] =
        new StreamBuilder()
    }
}

abstract class Stream[A, W[_]](skip: Int,
                               count: Option[Int])(implicit wrap: Wrap[W]) { self =>

  def headOption: W[Option[A]]
  def next(previous: A): W[Option[A]]

  def map[B](f: A => B): Stream[B, W] =
    new Stream[B, W](skip, count) {
      var previousA: Option[A] = Option.empty
      override def headOption: W[Option[B]] =
        self.headOption map {
          previousAOption =>
            previousA = previousAOption
            previousAOption.map(f)
        }
      override def next(previous: B): W[Option[B]] =
        previousA
          .map(self.next)
          .getOrElse(wrap.none[A])
          .map(_.map(f))
    }

  def foreach[U](f: A => U): Stream[Unit, W] =
    map[Unit](a => f(a))

  def flatMap[B](f: A => Stream[B, W]): W[Stream[B, W]] =
    foldLeft(wrap.success(new StreamBuilder[B, W]())) {
      (builder, next) =>
        builder flatMap {
          builder =>
            f(next).foldLeft(builder) {
              (builder, item) =>
                builder += item
            }
        }
    } flatMap (_.map(_.result))

  def filter(p: A => Boolean): W[Stream[A, W]] =
    foldLeft(new StreamBuilder[A, W]()) {
      (builder, item) =>
        if (p(item)) builder += item
        builder
    } map (_.result)

  def filterNot(p: A => Boolean): W[Stream[A, W]] =
    filter(!p(_))

  /**
    * Reads all items from the Stream and returns the last.
    */
  def lastOptionLinear: W[Option[A]] =
    foldLeft(Option.empty[A]) {
      (_, next) =>
        Some(next)
    }

  def toSeq: W[Seq[A]] =
    foldLeft(new StreamBuilder[A, W]()) {
      (buffer, item) =>
        buffer += item
    } map (_.asSeq)

  def foldLeft[B](initial: B)(f: (B, A) => B): W[B] =
    wrap(()) flatMap {
      _ =>
        wrap.foldLeft(initial, this, skip, count)(f)
    }

  def asFuture(implicit futureWrap: Wrap[Future]): Stream[A, Future] = {
    val stream: Stream[A, W] = this
    new Stream[A, Future](skip, count) {
      override def headOption: Future[Option[A]] = wrap.toFuture(stream.headOption)
      override def next(previous: A): Future[Option[A]] = wrap.toFuture(stream.next(previous))
    }
  }

  def asIO(implicit ioWrap: Wrap[IO]): Stream[A, IO] = {
    val stream: Stream[A, W] = this
    new Stream[A, IO](skip, count) {
      override def headOption: IO[Option[A]] = wrap.toIO(stream.headOption)
      override def next(previous: A): IO[Option[A]] = wrap.toIO(stream.next(previous))
    }
  }
}
