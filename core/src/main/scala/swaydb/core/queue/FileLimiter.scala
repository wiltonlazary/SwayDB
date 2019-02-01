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
package swaydb.core.queue

import com.typesafe.scalalogging.LazyLogging
import java.nio.file.Path
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.ref.WeakReference
import scala.util.Try

trait FileLimiter {

  def close(file: LimiterType): Unit

  def delete(file: LimiterType): Unit

}

trait LimiterType {
  def path: Path

  def delete(): Try[Unit]

  def close(): Try[Unit]

  def isOpen: Boolean
}

private[core] object FileLimiter extends LazyLogging {

  private sealed trait Action
  private object Action {
    case object Delete extends Action
    case object Close extends Action
  }

  val empty =
    new FileLimiter {
      override def close(file: LimiterType): Unit = ()

      override def delete(file: LimiterType): Unit = ()
    }

  private def weigher(entry: (WeakReference[LimiterType], Action)): Long =
    entry._1.get.map(_ => 1L) getOrElse 0L

  def apply(maxSegmentsOpen: Long, delay: FiniteDuration)(implicit ex: ExecutionContext): FileLimiter = {
    lazy val queue = LimitQueue[(WeakReference[LimiterType], Action)](maxSegmentsOpen, delay, weigher) {
      case (dbFile, action) =>
        action match {
          case Action.Delete =>
            dbFile.get foreach {
              file =>
                file.delete().failed foreach {
                  exception =>
                    logger.error(s"Failed to delete file. ${file.path}", exception)
                }
            }

          case Action.Close =>
            dbFile.get foreach {
              file =>
                file.close.failed foreach {
                  exception =>
                    logger.error(s"Failed to close file. ${file.path}", exception)
                }
            }
        }

    }

    new FileLimiter {
      override def close(file: LimiterType): Unit =
        queue ! (new WeakReference[LimiterType](file), Action.Close)

      override def delete(file: LimiterType): Unit =
        queue ! (new WeakReference[LimiterType](file), Action.Delete)
    }
  }
}