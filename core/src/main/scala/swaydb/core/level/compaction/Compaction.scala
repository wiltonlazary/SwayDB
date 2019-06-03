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

package swaydb.core.level.compaction

import com.typesafe.scalalogging.LazyLogging
import swaydb.core.data.Memory
import swaydb.core.level.zero.LevelZero
import swaydb.core.level.{LevelRef, NextLevel, TrashLevel}
import swaydb.core.segment.Segment
import swaydb.data.IO
import swaydb.data.slice.Slice

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * This object does not implement any concurrency which should be handled by an Actor.
  *
  * It just implements functions that given a Level and it's [[CompactorState]] mutates the state
  * such to reflect it's current compaction state. This state can then used to determine
  * how the next compaction should occur.
  *
  * State mutation is necessary to avoid unnecessary garbage during compaction. Functions returning Unit mutate the state.
  */
private[level] object Compaction extends LazyLogging {

  val awaitPullTimeout = 6.seconds.fromNow

  def run(state: CompactorState,
          forwardCopyOnAllLevels: Boolean): Unit =
    if (state.terminate)
      logger.debug(s"${state.id}: Ignoring wakeUp call. Compaction is terminated!")
    else
      runNow(
        state = state,
        forwardCopyOnAllLevels = forwardCopyOnAllLevels
      )

  private[compaction] def runNow(state: CompactorState,
                                 forwardCopyOnAllLevels: Boolean): Unit = {
    logger.debug(s"${state.id}: Running compaction now! forwardCopyOnAllLevels = $forwardCopyOnAllLevels!")
    if (forwardCopyOnAllLevels) {
      val totalCopies = copyForwardForEach(state.levelsReversed)(state.executionContext)
      logger.debug(s"${state.id}: Copies $totalCopies compacted. Continuing compaction.")
    }
    //run compaction jobs
    runJobs(
      state = state,
      currentJobs = state.levels.sorted(state.ordering)
    )
  }

  def shouldRun(level: LevelRef, state: LevelCompactionState): Boolean =
    state match {
      case awaitingPull @ LevelCompactionState.AwaitingPull(_, timeout, previousStateID) =>
        logger.debug(s"Level(${level.levelNumber}): $state")
        awaitingPull.isReady || timeout.isOverdue() || level.stateID != previousStateID

      case LevelCompactionState.Sleep(sleepDeadline, previousStateID) =>
        logger.debug(s"Level(${level.levelNumber}): $state")
        sleepDeadline.isOverdue() || level.stateID != previousStateID
    }

  @tailrec
  private[compaction] def runJobs(state: CompactorState,
                                  currentJobs: Slice[LevelRef]): Unit =
    if (state.terminate)
      logger.warn(s"${state.id}: Cannot run jobs. Compaction is terminated.")
    else
      currentJobs.headOption match {
        case Some(level) =>
          logger.debug(s"${state.id}: Running compaction.")
          if (state.compactionStates.get(level).forall(state => shouldRun(level, state))) {
            logger.debug(s"${state.id}: shouldRun = true.")
            val nextState = runJob(level)(state.executionContext)
            logger.debug(s"${state.id}: next state $nextState.")
            val willRun = shouldRun(level, nextState)
            logger.debug(s"${state.id}: shouldRun on nextState: $willRun. nextState: $nextState.")
            if (willRun)
              runJobs(state, currentJobs)
            else
              state.compactionStates.put(
                key = level,
                value = nextState
              )
          }
          else {
            logger.debug(s"${state.id}: shouldRun = false.")
            runJobs(state, currentJobs.dropHead())
          }

        case None =>
          //all jobs complete.
          logger.debug(s"${state.id}: Compaction round complete.")
      }

  private[compaction] def runJob(level: LevelRef)(implicit ec: ExecutionContext): LevelCompactionState =
    level match {
      case zero: LevelZero =>
        pushForward(zero = zero)

      case level: NextLevel =>
        pushForward(level = level)

      case TrashLevel =>
        logger.error(s"Level(${level.levelNumber}):Received job for ${TrashLevel.getClass.getSimpleName}.")
        //trash Levels should never error submitted for compaction anyway. Give it a long delay.
        LevelCompactionState.Sleep(365.days.fromNow, level.stateID)
    }

  private[compaction] def pushForward(level: NextLevel)(implicit ec: ExecutionContext): LevelCompactionState = {
    val throttle = level.throttle(level.meter)
    pushForward(level, throttle.segmentsToPush max 1) match {
      case IO.Success(pushed) =>
        logger.debug(s"Level(${level.levelNumber}): pushed $pushed Segments.")
        if (pushed == 0)
          LevelCompactionState.Sleep(
            sleepDeadline = 1.hour.fromNow,
            previousStateID = level.stateID
          )
        else
          LevelCompactionState.Sleep(
            sleepDeadline = level.nextCompactionDelay.fromNow,
            previousStateID = level.stateID
          )

      case later @ IO.Later(_, _) =>
        LevelCompactionState.AwaitingPull(
          later = later,
          timeout = awaitPullTimeout,
          previousStateID = level.stateID
        )

      case IO.Failure(_) =>
        LevelCompactionState.Sleep(
          sleepDeadline =
            if (LevelCompactionState.failureSleepDuration.timeLeft < throttle.pushDelay)
              LevelCompactionState.failureSleepDuration
            else
              throttle.pushDelay.fromNow,
          previousStateID = level.stateID
        )
    }
  }

  private[compaction] def pushForward(zero: LevelZero)(implicit ec: ExecutionContext): LevelCompactionState =
    zero.nextLevel map {
      nextLevel =>
        pushForward(
          zero = zero,
          nextLevel = nextLevel
        )
    } getOrElse LevelCompactionState.Sleep(365.days.fromNow, zero.stateID) //no nextLevel, no compaction!

  private[compaction] def pushForward(zero: LevelZero,
                                      nextLevel: NextLevel)(implicit ec: ExecutionContext): LevelCompactionState =
    zero.maps.last() match {
      case Some(map) =>
        logger.debug(s"Level(${zero.levelNumber}): Pushing LevelZero map :${map.pathOption}")
        pushForward(
          zero = zero,
          nextLevel = nextLevel,
          map = map
        )

      case None =>
        logger.debug(s"Level(${zero.levelNumber}): NO LAST MAP. No more maps to merge.")
        LevelCompactionState.Sleep(
          sleepDeadline = zero.throttle(zero.levelZeroMeter).fromNow,
          previousStateID = zero.stateID
        )
    }

  private[compaction] def pushForward(zero: LevelZero,
                                      nextLevel: NextLevel,
                                      map: swaydb.core.map.Map[Slice[Byte], Memory.SegmentResponse])(implicit ec: ExecutionContext): LevelCompactionState =
    nextLevel.put(map) match {
      case IO.Success(_) =>
        logger.debug(s"Level(${zero.levelNumber}): Put to map successful.")
        // If there is a failure removing the last map, maps will add the same map back into the queue and print
        // error message to be handled by the User.
        // Do not trigger another Push. This will stop LevelZero from pushing new memory maps to Level1.
        // Maps are ALWAYS required to be processed sequentially in the order of write.
        // Un order merging of maps should NOT be allowed.
        zero.maps.removeLast() foreach {
          result =>
            result onFailureSideEffect {
              error =>
                val mapPath: String = zero.maps.last().map(_.pathOption.map(_.toString).getOrElse("No path")).getOrElse("No map")
                logger.error(
                  s"Failed to delete the oldest memory map '$mapPath'. The map is added back to the memory-maps queue." +
                    "No more maps will be pushed to Level1 until this error is fixed " +
                    "as sequential conversion of memory-map files to Segments is required to maintain data accuracy. " +
                    "Please check file system permissions and ensure that SwayDB can delete files and reboot the database.",
                  error.exception
                )
            }
        }
        LevelCompactionState.Sleep(
          sleepDeadline = zero.throttle(zero.levelZeroMeter).fromNow,
          previousStateID = zero.stateID
        )

      case IO.Failure(exception) =>
        exception match {
          //do not log the stack if the IO.Failure to merge was ContainsOverlappingBusySegments.
          case IO.Error.OverlappingPushSegment =>
            logger.debug(s"Level(${zero.levelNumber}): Failed to push", IO.Error.OverlappingPushSegment.getClass.getSimpleName.dropRight(1))
          case _ =>
            logger.error(s"Level(${zero.levelNumber}): Failed to push", exception)
        }
        LevelCompactionState.Sleep(
          sleepDeadline = LevelCompactionState.failureSleepDuration,
          previousStateID = zero.stateID
        )

      case later @ IO.Later(_, _) =>
        LevelCompactionState.AwaitingPull(
          later = later,
          timeout = awaitPullTimeout,
          previousStateID = zero.stateID
        )
    }

  private[compaction] def pushForward(level: NextLevel,
                                      segmentsToPush: Int)(implicit ec: ExecutionContext): IO.Async[Int] =
    level.nextLevel map {
      nextLevel =>
        val (copyable, mergeable) = nextLevel.partitionUnreservedCopyable(level.segmentsInLevel())
        putForward(
          segments = copyable,
          thisLevel = level,
          nextLevel = nextLevel
        ) flatMap {
          copied =>
            if (segmentsToPush >= copied)
              IO.Success(copied)
            else
              putForward(
                segments = mergeable,
                thisLevel = level,
                nextLevel = nextLevel
              )
        }
    } getOrElse {
      runLastLevelCompaction(
        level = level,
        checkExpired = true,
        remainingCompactions = segmentsToPush,
        segmentsCompacted = 0
      ).asAsync
    }

  @tailrec
  def runLastLevelCompaction(level: NextLevel,
                             checkExpired: Boolean,
                             remainingCompactions: Int,
                             segmentsCompacted: Int)(implicit ec: ExecutionContext): IO[Int] =
    if (level.hasNextLevel || remainingCompactions <= 0)
      IO.Success(segmentsCompacted)
    else if (checkExpired)
      Segment.getNearestDeadlineSegment(level.segmentsInLevel()) match {
        case Some(segment) =>
          level.refresh(segment) match {
            case IO.Success(_) =>
              logger.debug(s"Level(${level.levelNumber}): Refresh successful.")
              runLastLevelCompaction(
                level = level,
                checkExpired = checkExpired,
                remainingCompactions = remainingCompactions - 1,
                segmentsCompacted = segmentsCompacted + 1
              )

            case IO.Later(_, _) =>
              logger.debug(s"Level(${level.levelNumber}): Later on refresh.")
              runLastLevelCompaction(
                level = level,
                checkExpired = false,
                remainingCompactions = remainingCompactions,
                segmentsCompacted = segmentsCompacted
              )

            case IO.Failure(_) =>
              runLastLevelCompaction(
                level = level,
                checkExpired = false,
                remainingCompactions = remainingCompactions,
                segmentsCompacted = segmentsCompacted
              )
          }

        case None =>
          runLastLevelCompaction(
            level = level,
            checkExpired = false,
            remainingCompactions = remainingCompactions,
            segmentsCompacted = segmentsCompacted
          )
      }
    else
      level.collapse(level.takeSmallSegments(remainingCompactions max 2)) match { //need at least 2 for collapse.
        case IO.Success(count) =>
          logger.debug(s"Level(${level.levelNumber}): Collapsed $count small segments.")
          runLastLevelCompaction(
            level = level,
            checkExpired = checkExpired,
            remainingCompactions = if (count == 0) 0 else remainingCompactions - count,
            segmentsCompacted = segmentsCompacted + count
          )

        case IO.Later(_, _) =>
          logger.debug(s"Level(${level.levelNumber}): Later on collapse.")
          runLastLevelCompaction(
            level = level,
            checkExpired = checkExpired,
            remainingCompactions = 0,
            segmentsCompacted = segmentsCompacted
          )

        case IO.Failure(_) =>
          runLastLevelCompaction(
            level = level,
            checkExpired = checkExpired,
            remainingCompactions = 0,
            segmentsCompacted = segmentsCompacted
          )
      }

  /**
    * Runs lazy error checks. Ignores all errors and continues copying
    * each Level starting from the lowest level first.
    */
  private[compaction] def copyForwardForEach(levels: Slice[LevelRef])(implicit ec: ExecutionContext): Int =
    levels.foldLeft(0) {
      case (totalCopies, level: NextLevel) =>
        val copied = copyForward(level)
        logger.debug(s"Level(${level.levelNumber}): Compaction copied $copied. Starting compaction!")
        totalCopies + copied

      case (copies, TrashLevel | _: LevelZero) =>
        copies
    }

  private def copyForward(level: NextLevel)(implicit ec: ExecutionContext): Int =
    level.nextLevel map {
      nextLevel =>
        val (copyable, _) = nextLevel.partitionUnreservedCopyable(level.segmentsInLevel())
        putForward(
          segments = copyable,
          thisLevel = level,
          nextLevel = nextLevel
        ) match {
          case IO.Success(copied) =>
            logger.debug(s"Level(${level.levelNumber}): Forward copied $copied Segments.")
            copied

          case IO.Failure(error) =>
            logger.error(s"Level(${level.levelNumber}): Failed copy Segments forward.", error.exception)
            0

          case IO.Later(_, _) =>
            //this should never really occur when no other concurrent compactions are occurring.
            logger.warn(s"Level(${level.levelNumber}): Received later compaction.")
            0
        }
    } getOrElse 0

  private[compaction] def putForward(segments: Iterable[Segment],
                                     thisLevel: NextLevel,
                                     nextLevel: NextLevel)(implicit ec: ExecutionContext): IO.Async[Int] =
    if (segments.isEmpty)
      IO.zero
    else
      nextLevel.put(segments) match {
        case IO.Success(_) =>
          thisLevel.removeSegments(segments) recoverWith {
            case _ =>
              IO.Success(segments.size)
          } asAsync

        case async @ IO.Later(_, _) =>
          async mapAsync (_ => 0)

        case IO.Failure(error) =>
          IO.Failure(error)
      }
}
