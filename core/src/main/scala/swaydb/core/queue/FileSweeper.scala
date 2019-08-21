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
package swaydb.core.queue

import java.nio.file.Path

import com.typesafe.scalalogging.LazyLogging
import swaydb.IO

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.ref.WeakReference

private[swaydb] trait FileSweeper {
  def close(file: FileSweeperItem): Unit
  def delete(file: FileSweeperItem): Unit
  def terminate(): Unit
}

private[core] trait FileSweeperItem {
  def path: Path
  def delete(): IO[swaydb.Error.Segment, Unit]
  def close(): IO[swaydb.Error.Segment, Unit]
  def isOpen: Boolean
}

private[core] object FileSweeper extends LazyLogging {

  val empty =
    new FileSweeper {
      override def close(file: FileSweeperItem): Unit = ()
      override def delete(file: FileSweeperItem): Unit = ()
      override def terminate(): Unit = ()
    }

  val none = Option.empty[FileSweeper]

  private sealed trait Action {
    def isDelete: Boolean
  }
  private object Action {
    case class Delete(file: FileSweeperItem) extends Action {
      def isDelete: Boolean = true
    }
    case class Close(file: WeakReference[FileSweeperItem]) extends Action {
      def isDelete: Boolean = false
    }
  }

  def weigher(action: Action) =
    if (action.isDelete) 10 else 1

  def apply(maxSegmentsOpen: Long, delay: FiniteDuration)(implicit ex: ExecutionContext): FileSweeper = {
    lazy val queue = LimitQueue[Action](maxSegmentsOpen, delay, weigher) {
      case Action.Delete(file) =>
        file.delete() onFailureSideEffect {
          error =>
            logger.error(s"Failed to delete file. ${file.path}", error.exception)
        }

      case Action.Close(file) =>
        file.get foreach {
          file =>
            file.close onFailureSideEffect {
              error =>
                logger.error(s"Failed to close file. ${file.path}", error.exception)
            }
        }
    }

    new FileSweeper {

      override def close(file: FileSweeperItem): Unit =
        queue ! Action.Close(new WeakReference[FileSweeperItem](file))

      //Delete cannot be a WeakReference because Levels can
      //remove references to the file after eventualDelete is invoked.
      //If the file gets garbage collected due to it being WeakReference before
      //delete on the file is triggered, the physical file will remain on disk.
      override def delete(file: FileSweeperItem): Unit =
        queue ! Action.Delete(file)

      override def terminate(): Unit =
        queue.terminate()
    }
  }
}