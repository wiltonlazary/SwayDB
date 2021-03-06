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

package swaydb.core.level

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Path, StandardOpenOption}

import com.typesafe.scalalogging.LazyLogging
import swaydb.Error.Level.ExceptionHandler
import swaydb.IO._
import swaydb.core.actor.{FileSweeper, MemorySweeper}
import swaydb.core.data.{KeyValue, _}
import swaydb.core.function.FunctionStore
import swaydb.core.io.file.Effect._
import swaydb.core.io.file.{BlockCache, Effect}
import swaydb.core.level.seek._
import swaydb.core.map.serializer._
import swaydb.core.map.{Map, MapEntry}
import swaydb.core.segment.format.a.block.binarysearch.BinarySearchIndexBlock
import swaydb.core.segment.format.a.block.bloomfilter.BloomFilterBlock
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock
import swaydb.core.segment.format.a.block.segment.SegmentBlock
import swaydb.core.segment.format.a.block.sortedindex.SortedIndexBlock
import swaydb.core.segment.format.a.block.values.ValuesBlock
import swaydb.core.segment.{Segment, SegmentAssigner, SegmentIO, SegmentOption, ThreadReadState}
import swaydb.core.util.Collections._
import swaydb.core.util.Exceptions._
import swaydb.core.util.{MinMax, _}
import swaydb.data.compaction.{LevelMeter, Throttle}
import swaydb.data.config.Dir
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice._
import swaydb.data.slice.{Slice, SliceOption}
import swaydb.data.storage.{AppendixStorage, LevelStorage}
import swaydb.{Error, IO}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

private[core] object Level extends LazyLogging {

  val emptySegmentsToPush = (Iterable.empty[Segment], Iterable.empty[Segment])

  def acquireLock(storage: LevelStorage.Persistent): IO[swaydb.Error.Level, Option[FileLock]] =
    IO {
      Effect createDirectoriesIfAbsent storage.dir
      val lockFile = storage.dir.resolve("LOCK")
      logger.info("{}: Acquiring lock.", lockFile)
      Effect createFileIfAbsent lockFile
      val lock = FileChannel.open(lockFile, StandardOpenOption.WRITE).tryLock()
      storage.dirs foreach {
        dir =>
          Effect createDirectoriesIfAbsent dir.path
      }
      Some(lock)
    }

  def acquireLock(levelStorage: LevelStorage): IO[swaydb.Error.Level, Option[FileLock]] =
    levelStorage match {
      case persistent: LevelStorage.Persistent =>
        acquireLock(persistent)

      case _: LevelStorage.Memory =>
        IO.none
    }

  def apply(bloomFilterConfig: BloomFilterBlock.Config,
            hashIndexConfig: HashIndexBlock.Config,
            binarySearchIndexConfig: BinarySearchIndexBlock.Config,
            sortedIndexConfig: SortedIndexBlock.Config,
            valuesConfig: ValuesBlock.Config,
            segmentConfig: SegmentBlock.Config,
            levelStorage: LevelStorage,
            appendixStorage: AppendixStorage,
            nextLevel: Option[NextLevel],
            throttle: LevelMeter => Throttle)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                              timeOrder: TimeOrder[Slice[Byte]],
                                              functionStore: FunctionStore,
                                              keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                              blockCache: Option[BlockCache.State],
                                              fileSweeper: FileSweeper.Enabled): IO[swaydb.Error.Level, Level] = {
    //acquire lock on folder
    acquireLock(levelStorage) flatMap {
      lock =>
        //lock acquired.
        //initialise Segment IO for this Level.
        implicit val segmentIO: SegmentIO =
          SegmentIO(
            bloomFilterConfig = bloomFilterConfig,
            hashIndexConfig = hashIndexConfig,
            binarySearchIndexConfig = binarySearchIndexConfig,
            sortedIndexConfig = sortedIndexConfig,
            valuesConfig = valuesConfig,
            segmentConfig = segmentConfig
          )

        //initialise readers & writers
        import AppendixMapEntryWriter.{AppendixPutWriter, AppendixRemoveWriter}

        val appendixReader =
          new AppendixMapEntryReader(
            mmapSegmentsOnRead = segmentConfig.mmapWrites,
            mmapSegmentsOnWrite = segmentConfig.mmapReads
          )

        import appendixReader._

        //default merger
        implicit val merger = AppendixSkipListMerger

        //initialise appendix
        val appendix: IO[swaydb.Error.Level, Map[SliceOption[Byte], SegmentOption, Slice[Byte], Segment]] =
          appendixStorage match {
            case AppendixStorage.Persistent(mmap, appendixFlushCheckpointSize) =>
              logger.info("{}: Initialising appendix.", levelStorage.dir)
              val appendixFolder = levelStorage.dir.resolve("appendix")
              //check if appendix folder/file was deleted.
              if ((!Effect.exists(appendixFolder) || appendixFolder.files(Extension.Log).isEmpty) && Effect.segmentFilesOnDisk(levelStorage.dirs.pathsSet.toSeq).nonEmpty) {
                logger.info("{}: Failed to start Level. Appendix file is missing", appendixFolder)
                IO.failed(new IllegalStateException(s"Failed to start Level. Appendix file is missing '$appendixFolder'."))
              } else {
                IO {
                  Effect createDirectoriesIfAbsent appendixFolder
                  Map.persistent[SliceOption[Byte], SegmentOption, Slice[Byte], Segment](
                    nullKey = Slice.Null,
                    nullValue = Segment.Null,
                    folder = appendixFolder,
                    mmap = mmap,
                    flushOnOverflow = true,
                    fileSize = appendixFlushCheckpointSize,
                    dropCorruptedTailEntries = false
                  ).item
                }
              }

            case AppendixStorage.Memory =>
              logger.info("{}: Initialising appendix for in-memory Level", levelStorage.dir)
              IO {
                Map.memory[SliceOption[Byte], SegmentOption, Slice[Byte], Segment](
                  Slice.Null,
                  Segment.Null
                )
              }
          }

        //initialise Level
        appendix flatMap {
          appendix =>
            logger.debug("{}: Checking Segments exist.", levelStorage.dir)
            //check that all existing Segments in appendix also exists on disk or else return error message.
            appendix.skipList.asScala foreachIO {
              case (_, segment) =>
                if (segment.existsOnDisk)
                  IO.unit
                else
                  IO.failed(swaydb.Exception.SegmentFileMissing(segment.path))
            } match {
              case Some(IO.Left(error)) =>
                IO.Left(error)

              case None =>
                logger.info("{}: Starting level.", levelStorage.dir)

                val allSegments = appendix.skipList.values().asScala
                implicit val segmentIDGenerator = IDGenerator(initial = largestSegmentId(allSegments))
                implicit val reserveRange = ReserveRange.create[Unit]()
                val paths: PathsDistributor = PathsDistributor(levelStorage.dirs, () => allSegments)

                val deletedUnCommittedSegments =
                  if (appendixStorage.persistent)
                    deleteUncommittedSegments(levelStorage.dirs, appendix.skipList.values().asScala)
                  else
                    IO.unit

                deletedUnCommittedSegments
                  .transform {
                    _ =>
                      new Level(
                        dirs = levelStorage.dirs,
                        bloomFilterConfig = bloomFilterConfig,
                        hashIndexConfig = hashIndexConfig,
                        binarySearchIndexConfig = binarySearchIndexConfig,
                        sortedIndexConfig = sortedIndexConfig,
                        valuesConfig = valuesConfig,
                        segmentConfig = segmentConfig,
                        inMemory = levelStorage.memory,
                        throttle = throttle,
                        nextLevel = nextLevel,
                        appendix = appendix,
                        lock = lock,
                        pathDistributor = paths,
                        removeDeletedRecords = Level.removeDeletes(nextLevel)
                      )
                  }
            }
        }
    }
  }

  def removeDeletes(nextLevel: Option[LevelRef]): Boolean =
    nextLevel.isEmpty || nextLevel.exists(_.isTrash)

  def largestSegmentId(appendix: Iterable[Segment]): Long =
    appendix.foldLeft(0L) {
      case (initialId, segment) =>
        val segmentId = segment.path.fileId._1
        if (initialId > segmentId)
          initialId
        else
          segmentId
    }

  /**
   * A Segment is considered small if it's size is less than 40% of the default [[Level.minSegmentSize]]
   */
  def isSmallSegment(segment: Segment, levelSegmentSize: Long): Boolean =
    segment.segmentSize < levelSegmentSize * 0.40

  def deleteUncommittedSegments(dirs: Seq[Dir], appendixSegments: Iterable[Segment]): IO[swaydb.Error.Level, Unit] =
    dirs
      .flatMap(_.path.files(Extension.Seg))
      .foreachIO {
        segmentToDelete =>

          val toDelete =
            appendixSegments.foldLeft(true) {
              case (toDelete, appendixSegment) =>
                if (appendixSegment.path == segmentToDelete)
                  false
                else
                  toDelete
            }

          if (toDelete) {
            logger.info("SEGMENT {} not in appendix. Deleting uncommitted segment.", segmentToDelete)
            IO(Effect.delete(segmentToDelete))
          } else {
            IO.unit
          }
      }
      .getOrElse(IO.unit)

  def optimalSegmentsToPushForward(level: NextLevel,
                                   nextLevel: NextLevel,
                                   take: Int)(implicit reserve: ReserveRange.State[Unit],
                                              keyOrder: KeyOrder[Slice[Byte]]): (Iterable[Segment], Iterable[Segment]) = {
    val segmentsToCopy = ListBuffer.empty[Segment]
    val segmentsToMerge = ListBuffer.empty[Segment]
    level
      .segmentsInLevel()
      .foreachBreak {
        segment =>
          if (ReserveRange.isUnreserved(segment) && nextLevel.isUnreserved(segment))
            if (!Segment.overlaps(segment, nextLevel.segmentsInLevel()))
              segmentsToCopy += segment
            else if (segmentsToMerge.size < take) //only cache enough Segments to merge.
              segmentsToMerge += segment

          segmentsToCopy.size >= take
      }
    (segmentsToCopy, segmentsToMerge)
  }

  def shouldCollapse(level: NextLevel,
                     segment: Segment)(implicit reserve: ReserveRange.State[Unit],
                                       keyOrder: KeyOrder[Slice[Byte]]): Boolean =
    ReserveRange.isUnreserved(segment) && {
      isSmallSegment(segment, level.minSegmentSize) ||
        //if the Segment was not created in this level.
        segment.createdInLevel != level.levelNumber
    }

  def optimalSegmentsToCollapse(level: NextLevel,
                                take: Int)(implicit reserve: ReserveRange.State[Unit],
                                           keyOrder: KeyOrder[Slice[Byte]]): Iterable[Segment] = {
    var segmentsTaken: Int = 0
    val segmentsToCollapse = ListBuffer.empty[Segment]

    level
      .segmentsInLevel()
      .foreachBreak {
        segment =>
          if (shouldCollapse(level, segment)) {
            segmentsToCollapse += segment
            segmentsTaken += 1
          }

          segmentsTaken >= take
      }

    segmentsToCollapse
  }

  def delete(level: NextLevel): IO[swaydb.Error.Delete, Unit] =
    level
      .close
      .flatMap {
        _ =>
          import swaydb.Error.Delete.ExceptionHandler

          level
            .nextLevel
            .map(_.delete)
            .getOrElse {
              level
                .pathDistributor
                .dirs
                .foreachIO {
                  path =>
                    IO(Effect.walkDelete(path.path))
                }
                .getOrElse(IO.unit)
            }
      }
}

private[core] case class Level(dirs: Seq[Dir],
                               bloomFilterConfig: BloomFilterBlock.Config,
                               hashIndexConfig: HashIndexBlock.Config,
                               binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                               sortedIndexConfig: SortedIndexBlock.Config,
                               valuesConfig: ValuesBlock.Config,
                               segmentConfig: SegmentBlock.Config,
                               inMemory: Boolean,
                               throttle: LevelMeter => Throttle,
                               nextLevel: Option[NextLevel],
                               appendix: Map[SliceOption[Byte], SegmentOption, Slice[Byte], Segment],
                               lock: Option[FileLock],
                               pathDistributor: PathsDistributor,
                               removeDeletedRecords: Boolean)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                              timeOrder: TimeOrder[Slice[Byte]],
                                                              functionStore: FunctionStore,
                                                              removeWriter: MapEntryWriter[MapEntry.Remove[Slice[Byte]]],
                                                              addWriter: MapEntryWriter[MapEntry.Put[Slice[Byte], Segment]],
                                                              keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                                              val fileSweeper: FileSweeper.Enabled,
                                                              val blockCache: Option[BlockCache.State],
                                                              val segmentIDGenerator: IDGenerator,
                                                              val segmentIO: SegmentIO,
                                                              reserve: ReserveRange.State[Unit]) extends NextLevel with LazyLogging { self =>

  logger.info(s"{}: Level started.", pathDistributor)

  override val levelNumber: Int =
    pathDistributor
      .head
      .path
      .folderId
      .toInt

  private implicit val currentWalker =
    new CurrentWalker {
      override def get(key: Slice[Byte],
                       readState: ThreadReadState): KeyValue.PutOption =
        self.get(key, readState)

      override def higher(key: Slice[Byte], readState: ThreadReadState): LevelSeek[KeyValue] =
        higherInThisLevel(key, readState)

      override def lower(key: Slice[Byte], readState: ThreadReadState): LevelSeek[KeyValue] =
        self.lowerInThisLevel(key, readState)

      override def levelNumber: String =
        "Level: " + self.levelNumber
    }

  private implicit val nextWalker =
    new NextWalker {
      override def higher(key: Slice[Byte],
                          readState: ThreadReadState): KeyValue.PutOption =
        higherInNextLevel(key, readState)

      override def lower(key: Slice[Byte],
                         readState: ThreadReadState): KeyValue.PutOption =
        lowerFromNextLevel(key, readState)

      override def get(key: Slice[Byte],
                       readState: ThreadReadState): KeyValue.PutOption =
        getFromNextLevel(key, readState)

      override def levelNumber: String =
        "Level: " + self.levelNumber
    }

  private implicit val currentGetter =
    new CurrentGetter {
      override def get(key: Slice[Byte],
                       readState: ThreadReadState): KeyValueOption =
        getFromThisLevel(key, readState)
    }

  val meter =
    new LevelMeter {
      override def segmentsCount: Int =
        self.segmentsCount()

      override def levelSize: Long =
        self.levelSize

      override def requiresCleanUp: Boolean =
        !hasNextLevel && self.shouldSelfCompactOrExpire

      override def nextLevelMeter: Option[LevelMeter] =
        nextLevel.map(_.meter)

      override def segmentCountAndLevelSize: (Int, Long) =
        self.segmentCountAndLevelSize
    }

  def rootPath: Path =
    dirs.head.path

  def appendixPath: Path =
    rootPath.resolve("appendix")

  def releaseLocks: IO[swaydb.Error.Close, Unit] =
    IO[swaydb.Error.Close, Unit](Effect.release(lock)) flatMap {
      _ =>
        nextLevel.map(_.releaseLocks) getOrElse IO.unit
    }

  def nextPushDelay: FiniteDuration =
    throttle(meter).pushDelay

  def nextBatchSize: Int =
    throttle(meter).segmentsToPush

  def nextPushDelayAndBatchSize =
    throttle(meter)

  def nextPushDelayAndSegmentsCount: (FiniteDuration, Int) = {
    val stats = meter
    (throttle(stats).pushDelay, stats.segmentsCount)
  }

  def nextBatchSizeAndSegmentsCount: (Int, Int) = {
    val stats = meter
    (throttle(stats).segmentsToPush, stats.segmentsCount)
  }

  private[level] implicit def reserve(segments: Iterable[Segment]): IO[Error.Level, IO[Promise[Unit], Slice[Byte]]] =
    IO {
      SegmentAssigner.assignMinMaxOnlyUnsafe(
        inputSegments = segments,
        targetSegments = appendix.skipList.values().asScala
      )
    } map {
      assigned =>
        Segment.minMaxKey(
          left = assigned,
          right = segments
        ) match {
          case Some((minKey, maxKey, toInclusive)) =>
            ReserveRange.reserveOrListen(
              from = minKey,
              to = maxKey,
              toInclusive = toInclusive,
              info = ()
            )

          case None =>
            IO.Left(Promise.successful(()))(IO.ExceptionHandler.PromiseUnit)
        }
    }

  private[level] implicit def reserve(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory]): IO[Error.Level, IO[Promise[Unit], Slice[Byte]]] =
    IO {
      SegmentAssigner.assignMinMaxOnlyUnsafe(
        map = map,
        targetSegments = appendix.skipList.values().asScala
      )
    } map {
      assigned =>
        Segment.minMaxKey(
          left = assigned,
          right = map
        ) match {
          case Some((minKey, maxKey, toInclusive)) =>
            ReserveRange.reserveOrListen(
              from = minKey,
              to = maxKey,
              toInclusive = toInclusive,
              info = ()
            )

          case None =>
            IO.Left(Promise.successful(()))(IO.ExceptionHandler.PromiseUnit)
        }
    }

  def partitionUnreservedCopyable(segments: Iterable[Segment]): (Iterable[Segment], Iterable[Segment]) =
    segments partition {
      segment =>
        ReserveRange.isUnreserved(
          from = segment.minKey,
          to = segment.maxKey.maxKey,
          toInclusive = segment.maxKey.inclusive
        ) && {
          !Segment.overlaps(
            segment = segment,
            segments2 = segmentsInLevel()
          )
        }
    }

  def isCopyable(minKey: Slice[Byte], maxKey: Slice[Byte], maxKeyInclusive: Boolean): Boolean =
    ReserveRange.isUnreserved(
      from = minKey,
      to = maxKey,
      toInclusive = maxKeyInclusive
    ) && {
      !Segment.overlaps(
        minKey = minKey,
        maxKey = maxKey,
        maxKeyInclusive = maxKeyInclusive,
        segments = segmentsInLevel()
      )
    }

  def isCopyable(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory]): Boolean =
    Segment
      .minMaxKey(map)
      .forall {
        case (minKey, maxKey, maxInclusive) =>
          isCopyable(
            minKey = minKey,
            maxKey = maxKey,
            maxKeyInclusive = maxInclusive
          )
      }

  def isUnreserved(minKey: Slice[Byte], maxKey: Slice[Byte], maxKeyInclusive: Boolean): Boolean =
    ReserveRange.isUnreserved(
      from = minKey,
      to = maxKey,
      toInclusive = maxKeyInclusive
    )

  def isUnreserved(segment: Segment): Boolean =
    isUnreserved(
      minKey = segment.minKey,
      maxKey = segment.maxKey.maxKey,
      maxKeyInclusive = segment.maxKey.inclusive
    )

  def reserveAndRelease[I, T](segments: I)(f: => IO[swaydb.Error.Level, T])(implicit tryReserve: I => IO[swaydb.Error.Level, IO[Promise[Unit], Slice[Byte]]]): IO[Promise[Unit], IO[swaydb.Error.Level, T]] =
    tryReserve(segments) map {
      reserveOutcome =>
        reserveOutcome map {
          minKey =>
            try
              f
            finally
              ReserveRange.free(minKey)
        }
    } match {
      case IO.Right(either) =>
        either

      case IO.Left(error) =>
        IO.Right(IO.Left[swaydb.Error.Level, T](error))(IO.ExceptionHandler.PromiseUnit)
    }

  def put(segment: Segment): IO[Promise[Unit], IO[swaydb.Error.Level, Set[Int]]] =
    put(Seq(segment))

  def put(segments: Iterable[Segment]): IO[Promise[Unit], IO[swaydb.Error.Level, Set[Int]]] = {
    logger.trace(s"{}: Putting segments '{}' segments.", pathDistributor.head, segments.map(_.path.toString).toList)
    reserveAndRelease(segments) {
      val appendixSegments = appendix.skipList.values().asScala
      val (segmentToMerge, segmentToCopy) = Segment.partitionOverlapping(segments, appendixSegments)
      put(
        segmentsToMerge = segmentToMerge,
        segmentsToCopy = segmentToCopy,
        targetSegments = appendixSegments
      )
    }
  }

  private def deleteCopiedSegments(copiedSegments: Iterable[Segment]) =
    if (segmentConfig.deleteEventually)
      copiedSegments foreach (_.deleteSegmentsEventually)
    else
      copiedSegments
        .foreach {
          segmentToDelete =>
            IO(segmentToDelete.delete)
              .onLeftSideEffect {
                failure =>
                  logger.error(s"{}: Failed to delete copied Segment '{}'", pathDistributor.head, segmentToDelete.path, failure)
              }
        }

  private[level] def put(segmentsToMerge: Iterable[Segment],
                         segmentsToCopy: Iterable[Segment],
                         targetSegments: Iterable[Segment]): IO[swaydb.Error.Level, Set[Int]] =
    if (segmentsToCopy.nonEmpty)
      copyForwardOrCopyLocal(segmentsToCopy) flatMap {
        newlyCopiedSegments =>
          if (newlyCopiedSegments.value.nonEmpty) //Segments were copied into this Level.
            buildNewMapEntry(newlyCopiedSegments.value, Segment.Null, None) flatMap {
              copiedSegmentsEntry =>
                val putResult: IO[swaydb.Error.Level, _] =
                  if (segmentsToMerge.nonEmpty)
                    merge(
                      segments = segmentsToMerge,
                      targetSegments = targetSegments,
                      appendEntry = Some(copiedSegmentsEntry)
                    )
                  else
                    appendix.writeSafe(copiedSegmentsEntry)

                putResult
                  .transform {
                    _ =>
                      newlyCopiedSegments.levelsUpdated + levelNumber
                  }
                  .onLeftSideEffect {
                    failure =>
                      logFailure(s"${pathDistributor.head}: Failed to create a log entry. Deleting ${newlyCopiedSegments.value.size} copied segments", failure)
                      deleteCopiedSegments(newlyCopiedSegments.value)
                  }
            }
          else if (segmentsToMerge.nonEmpty) //check if there are Segments to merge.
            merge( //no segments were copied. Do merge!
              segments = segmentsToMerge,
              targetSegments = targetSegments,
              appendEntry = None
            ) transform {
              _ =>
                newlyCopiedSegments.levelsUpdated + levelNumber
            }
          else
            IO.Right(newlyCopiedSegments.levelsUpdated)
      }
    else
      merge(
        segments = segmentsToMerge,
        targetSegments = targetSegments,
        appendEntry = None
      ) transform {
        _ =>
          Set(levelNumber)
      }

  def put(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory]): IO[Promise[Unit], IO[swaydb.Error.Level, Set[Int]]] = {
    logger.trace("{}: PutMap '{}' Maps.", pathDistributor.head, map.skipList.count())
    reserveAndRelease(map) {
      val appendixValues = appendix.skipList.values().asScala
      if (Segment.overlaps(map, appendixValues))
        putKeyValues(
          keyValuesCount = map.skipListKeyValuesMaxCount,
          keyValues = map.skipList.values().asScala,
          targetSegments = appendixValues,
          appendEntry = None
        ) transform {
          _ =>
            Set(levelNumber)
        }
      else
        copyForwardOrCopyLocal(map)
          .flatMap {
            newSegments =>
              if (newSegments.value.nonEmpty)
                buildNewMapEntry(newSegments.value, Segment.Null, None)
                  .flatMap {
                    entry =>
                      appendix
                        .writeSafe(entry)
                        .transform(_ => newSegments.levelsUpdated)
                  }
                  .onLeftSideEffect {
                    failure =>
                      logFailure(s"${pathDistributor.head}: Failed to create a log entry.", failure)
                      deleteCopiedSegments(newSegments.value)
                  }
              else
                IO.Right(newSegments.levelsUpdated)
          }
    }
  }

  /**
   * @return empty if copied into next Level else Segments copied into this Level.
   */
  private def copyForwardOrCopyLocal(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory]): IO[swaydb.Error.Level, CompactionResult[Iterable[Segment]]] = {
    val forwardResult = forward(map)
    if (forwardResult.value)
      IO.Right(forwardResult.updateValue(Segment.emptyIterable))
    else
      copy(map).transform {
        copied =>
          CompactionResult(
            value = copied,
            levelUpdated = levelNumber
          )
      }
  }

  /**
   * Returns segments that were not forwarded.
   */
  private def forward(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory]): CompactionResult[Boolean] = {
    logger.trace(s"{}: forwarding {} Map. pushForward = ${segmentConfig.pushForward}", pathDistributor.head, map.pathOption)
    if (segmentConfig.pushForward)
      nextLevel match {
        case Some(nextLevel) if nextLevel.isCopyable(map) =>
          nextLevel.put(map) match {
            case IO.Right(result) =>
              result match {
                case IO.Right(levelsUpdated) =>
                  CompactionResult(
                    value = true,
                    levelsUpdated = levelsUpdated
                  )

                case IO.Left(_) =>
                  CompactionResult.`false`
              }

            case IO.Left(_) =>
              CompactionResult.`false`
          }

        case Some(_) =>
          CompactionResult.`false`

        case None =>
          CompactionResult.`false`
      }
    else
      CompactionResult.`false`
  }

  private[level] def copy(map: Map[SliceOption[Byte], MemoryOption, Slice[Byte], Memory])(implicit blockCache: Option[BlockCache.State]): IO[swaydb.Error.Level, Iterable[Segment]] =
    IO {
      logger.trace(s"{}: Copying {} Map", pathDistributor.head, map.pathOption)

      if (inMemory)
        Segment.copyToMemory(
          keyValues = map.skipList.values().iterator().asScala,
          pathsDistributor = pathDistributor,
          removeDeletes = removeDeletedRecords,
          minSegmentSize = minSegmentSize,
          maxKeyValueCountPerSegment = segmentConfig.maxCount,
          createdInLevel = levelNumber
        )
      else
        Segment.copyToPersist(
          keyValues = map.skipList.values().asScala,
          segmentConfig = segmentConfig,
          createdInLevel = levelNumber,
          pathsDistributor = pathDistributor,
          removeDeletes = removeDeletedRecords,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig
        )
    }

  /**
   * Returns newly created Segments in this Level.
   */
  private def copyForwardOrCopyLocal(segments: Iterable[Segment]): IO[swaydb.Error.Level, CompactionResult[Iterable[Segment]]] = {
    val segmentsNotForwarded = forward(segments)
    if (segmentsNotForwarded.value.isEmpty)
      IO.Right(segmentsNotForwarded) //all were forwarded.
    else
      copyLocal(segmentsNotForwarded.value) transform {
        newSegments =>
          CompactionResult(
            value = newSegments,
            levelsUpdated = segmentsNotForwarded.levelsUpdated + levelNumber
          )
      }
  }

  /**
   * @return segments that were not forwarded.
   */
  private def forward(segments: Iterable[Segment]): CompactionResult[Iterable[Segment]] = {
    logger.trace(s"{}: Copying forward {} Segments. pushForward = ${segmentConfig.pushForward}", pathDistributor.head, segments.map(_.path.toString))
    if (segmentConfig.pushForward)
      nextLevel match {
        case Some(nextLevel) =>
          val (copyable, nonCopyable) = nextLevel partitionUnreservedCopyable segments
          if (copyable.isEmpty)
            CompactionResult(segments)
          else
            nextLevel.put(copyable) match {
              case IO.Right(forwardResult) =>
                forwardResult match {
                  case IO.Right(forwardedTo) =>
                    CompactionResult(
                      value = nonCopyable,
                      levelsUpdated = forwardedTo
                    )

                  case IO.Left(_) =>
                    CompactionResult(segments)
                }

              case IO.Left(_) =>
                CompactionResult(segments)
            }

        case None =>
          CompactionResult(segments)
      }
    else
      CompactionResult(segments)
  }

  private[level] def copyLocal(segments: Iterable[Segment])(implicit blockCache: Option[BlockCache.State]): IO[swaydb.Error.Level, Iterable[Segment]] = {
    logger.trace(s"{}: Copying {} Segments", pathDistributor.head, segments.map(_.path.toString))
    segments.flatMapRecoverIO[Segment](
      ioBlock =
        segment =>
          IO {
            if (inMemory)
              Segment.copyToMemory(
                segment = segment,
                createdInLevel = levelNumber,
                pathsDistributor = pathDistributor,
                removeDeletes = removeDeletedRecords,
                minSegmentSize = segmentConfig.minSize,
                maxKeyValueCountPerSegment = segmentConfig.maxCount
              )
            else
              Segment.copyToPersist(
                segment = segment,
                segmentConfig = segmentConfig,
                createdInLevel = levelNumber,
                pathsDistributor = pathDistributor,
                removeDeletes = removeDeletedRecords,
                valuesConfig = valuesConfig,
                sortedIndexConfig = sortedIndexConfig,
                binarySearchIndexConfig = binarySearchIndexConfig,
                hashIndexConfig = hashIndexConfig,
                bloomFilterConfig = bloomFilterConfig
              )
          },
      recover =
        (segments, failure) => {
          logFailure(s"${pathDistributor.head}: Failed to copy Segments. Deleting partially copied Segments ${segments.size} Segments", failure)
          segments foreach {
            segment =>
              IO(segment.delete) onLeftSideEffect {
                failure =>
                  logger.error(s"{}: Failed to delete copied Segment '{}'", pathDistributor.head, segment.path, failure)
              }
          }
        }
    )
  }

  def refresh(segment: Segment): IO[Promise[Unit], IO[swaydb.Error.Level, Unit]] = {
    logger.debug("{}: Running refresh.", pathDistributor.head)
    reserveAndRelease(Seq(segment)) {
      IO {
        segment.refresh(
          removeDeletes = removeDeletedRecords,
          createdInLevel = levelNumber,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig = segmentConfig,
          pathsDistributor = pathDistributor
        )
      } flatMap {
        newSegments =>
          logger.debug(s"{}: Segment {} successfully refreshed. New Segments: {}.", pathDistributor.head, segment.path, newSegments.map(_.path).mkString(", "))
          buildNewMapEntry(newSegments, segment, None) flatMap {
            entry =>
              appendix.writeSafe(entry).transform(_ => ()) onRightSideEffect {
                _ =>
                  if (segmentConfig.deleteEventually)
                    segment.deleteSegmentsEventually
                  else
                    IO(segment.delete) onLeftSideEffect {
                      failure =>
                        logger.error(s"Failed to delete Segments '{}'. Manually delete these Segments or reboot the database.", segment.path, failure)
                    }
              }
          } onLeftSideEffect {
            _ =>
              newSegments foreach {
                segment =>
                  IO(segment.delete) onLeftSideEffect {
                    failure =>
                      logger.error(s"{}: Failed to delete Segment {}", pathDistributor.head, segment.path, failure)
                  }
              }
          }
      }
    }
  }

  def removeSegments(segments: Iterable[Segment]): IO[swaydb.Error.Level, Int] = {
    //create this list which is a copy of segments. Segments can be iterable only once if it's a Java iterable.
    //this copy is for second read to delete the segments after the MapEntry is successfully created.
    logger.trace(s"{}: Removing Segments {}", pathDistributor.head, segments.map(_.path.toString))
    val segmentsToRemove = Slice.create[Segment](segments.size)

    val mapEntry =
      segments
        .foldLeft(Option.empty[MapEntry[Slice[Byte], Segment]]) {
          case (previousEntry, segmentToRemove) =>
            segmentsToRemove add segmentToRemove
            val nextEntry = MapEntry.Remove[Slice[Byte]](segmentToRemove.minKey)
            previousEntry.map(_ ++ nextEntry) orElse Some(nextEntry)
        }

    mapEntry match {
      case Some(mapEntry) =>
        //        logger.info(s"$id. Build map entry: ${mapEntry.string(_.asInt().toString, _.id.toString)}")
        logger.trace(s"{}: Built map entry to remove Segments {}", pathDistributor.head, segments.map(_.path.toString))
        appendix.writeSafe(mapEntry) flatMap {
          _ =>
            logger.debug(s"{}: MapEntry delete Segments successfully written. Deleting physical Segments: {}", pathDistributor.head, segments.map(_.path.toString))
            // If a delete fails that would be due OS permission issue.
            // But it's OK if it fails as long as appendix is updated with new segments. An error message will be logged
            // asking to delete the uncommitted segments manually or do a database restart which will delete the uncommitted
            // Segments on reboot.
            if (segmentConfig.deleteEventually) {
              segmentsToRemove foreach (_.deleteSegmentsEventually)
              IO.zero
            } else {
              IO(Segment.deleteSegments(segmentsToRemove)) recoverWith {
                case exception =>
                  logger.error(s"Failed to delete Segments '{}'. Manually delete these Segments or reboot the database.", segmentsToRemove.map(_.path.toString).mkString(", "), exception)
                  IO.zero
              }
            }
        }

      case None =>
        IO.Left[swaydb.Error.Level, Int](swaydb.Error.NoSegmentsRemoved)
    }
  }

  def collapse(segments: Iterable[Segment]): IO[Promise[Unit], IO[swaydb.Error.Level, Int]] = {
    logger.trace(s"{}: Collapsing '{}' segments", pathDistributor.head, segments.size)
    if (segments.isEmpty || appendix.size == 1) { //if there is only one Segment in this Level which is a small segment. No collapse required
      IO.Right[Promise[Unit], IO[swaydb.Error.Level, Int]](IO.zero)(IO.ExceptionHandler.PromiseUnit)
    } else {
      //other segments in the appendix that are not the input segments (segments to collapse).
      val levelSegments = segmentsInLevel()
      val targetAppendixSegments = levelSegments.filterNot(map => segments.exists(_.path == map.path))

      val (segmentsToMerge, targetSegments) =
        if (targetAppendixSegments.nonEmpty) {
          logger.trace(s"{}: Target appendix segments {}", pathDistributor.head, targetAppendixSegments.size)
          (segments, targetAppendixSegments)
        } else {
          //If appendix without the small Segments is empty.
          // Then pick the first segment from the smallest segments and merge other small segments into it.
          val firstToCollapse = Iterable(segments.head)
          logger.trace(s"{}: Target segments {}", pathDistributor.head, firstToCollapse.size)
          (segments.drop(1), firstToCollapse)
        }

      //reserve the Level. It's unknown here what segments will value collapsed into what other Segments.
      reserveAndRelease(levelSegments) {
        //create an appendEntry that will remove smaller segments from the appendix.
        //this entry will value applied only if the putSegments is successful orElse this entry will be discarded.
        val appendEntry =
        segmentsToMerge.foldLeft(Option.empty[MapEntry[Slice[Byte], Segment]]) {
          case (mapEntry, smallSegment) =>
            val entry = MapEntry.Remove(smallSegment.minKey)
            mapEntry.map(_ ++ entry) orElse Some(entry)
        }

        merge(
          segments = segmentsToMerge,
          targetSegments = targetSegments,
          appendEntry = appendEntry
        ) transform {
          _ =>
            //delete the segments merged with self.
            if (segmentConfig.deleteEventually)
              segmentsToMerge foreach (_.deleteSegmentsEventually)
            else
              segmentsToMerge foreach {
                segment =>
                  IO(segment.delete) onLeftSideEffect {
                    exception =>
                      logger.warn(s"{}: Failed to delete Segment {} after successful collapse", pathDistributor.head, segment.path, exception)
                  }
              }
            segmentsToMerge.size
        }
      }
    }
  }

  private def merge(segments: Iterable[Segment],
                    targetSegments: Iterable[Segment],
                    appendEntry: Option[MapEntry[Slice[Byte], Segment]]): IO[swaydb.Error.Level, Unit] = {
    logger.trace(s"{}: Merging segments {}", pathDistributor.head, segments.map(_.path.toString))
    IO(Segment.getAllKeyValues(segments))
      .flatMap {
        keyValues =>
          putKeyValues(
            keyValuesCount = keyValues.size,
            keyValues = keyValues,
            targetSegments = targetSegments,
            appendEntry = appendEntry
          )
      }
  }

  /**
   * @return Newly created Segments.
   */
  private[core] def putKeyValues(keyValuesCount: Int,
                                 keyValues: Iterable[KeyValue],
                                 targetSegments: Iterable[Segment],
                                 appendEntry: Option[MapEntry[Slice[Byte], Segment]]): IO[swaydb.Error.Level, Unit] = {
    logger.trace(s"{}: Merging {} KeyValues.", pathDistributor.head, keyValues.size)
    IO(SegmentAssigner.assignUnsafe(keyValuesCount, keyValues, targetSegments)) flatMap {
      assignments =>
        logger.trace(s"{}: Assigned segments {} for {} KeyValues.", pathDistributor.head, assignments.map(_._1.path.toString), keyValues.size)
        if (assignments.isEmpty) {
          logger.error(s"{}: Assigned segments are empty. Cannot merge Segments to empty target Segments: {}.", pathDistributor.head, keyValues.size)
          IO.Left[swaydb.Error.Level, Unit](swaydb.Error.MergeKeyValuesWithoutTargetSegment(keyValues.size))
        } else {
          logger.debug(s"{}: Assigned segments {}. Merging {} KeyValues.", pathDistributor.head, assignments.map(_._1.path.toString), keyValues.size)
          putAssignedKeyValues(assignments) flatMap {
            targetSegmentAndNewSegments =>
              targetSegmentAndNewSegments.foldLeftRecoverIO(Option.empty[MapEntry[Slice[Byte], Segment]]) {
                case (mapEntry, (targetSegment, newSegments)) =>
                  buildNewMapEntry(newSegments, targetSegment, mapEntry).toOptionValue
              } flatMap {
                case Some(mapEntry) =>
                  //also write appendEntry to this mapEntry before committing entries to appendix.
                  //Note: appendEntry should not overwrite new Segment's entries with same keys so perform distinct
                  //which will remove oldEntries with duplicates with newer keys.
                  val mapEntryToWrite = appendEntry.map(appendEntry => MapEntry.distinct(mapEntry, appendEntry)) getOrElse mapEntry
                  appendix.writeSafe(mapEntryToWrite) transform {
                    _ =>
                      logger.debug(s"{}: putKeyValues successful. Deleting assigned Segments. {}.", pathDistributor.head, assignments.map(_._1.path.toString))
                      //delete assigned segments as they are replaced with new segments.
                      if (segmentConfig.deleteEventually)
                        assignments foreach (_._1.deleteSegmentsEventually)
                      else
                        assignments foreach {
                          case (segment, _) =>
                            IO(segment.delete) onLeftSideEffect {
                              exception =>
                                logger.error(s"{}: Failed to delete Segment {}", pathDistributor.head, segment.path, exception)
                            }
                        }
                  }

                case None =>
                  IO.failed(s"${pathDistributor.head}: Failed to create map entry")
              } onLeftSideEffect {
                failure =>
                  logFailure(s"${pathDistributor.head}: Failed to write key-values. Reverting", failure)
                  targetSegmentAndNewSegments foreach {
                    case (_, newSegments) =>
                      newSegments foreach {
                        segment =>
                          IO(segment.delete) onLeftSideEffect {
                            exception =>
                              logger.error(s"{}: Failed to delete Segment {}", pathDistributor.head, segment.path, exception)
                          }
                      }
                  }
              }
          }
        }
    }
  }

  private def putAssignedKeyValues(assignedSegments: mutable.Map[Segment, Slice[KeyValue]]): IO[swaydb.Error.Level, Slice[(Segment, Slice[Segment])]] =
    assignedSegments.mapRecoverIO[(Segment, Slice[Segment])](
      block = {
        case (targetSegment, assignedKeyValues) =>
          IO {
            val newSegments =
              targetSegment.put(
                newKeyValues = assignedKeyValues,
                removeDeletes = removeDeletedRecords,
                createdInLevel = levelNumber,
                valuesConfig = valuesConfig,
                sortedIndexConfig = sortedIndexConfig,
                binarySearchIndexConfig = binarySearchIndexConfig,
                hashIndexConfig = hashIndexConfig,
                bloomFilterConfig = bloomFilterConfig,
                segmentConfig = segmentConfig,
                pathsDistributor = pathDistributor.addPriorityPath(targetSegment.path.getParent)
              )
            (targetSegment, newSegments)
          }
      },
      recover =
        (targetSegmentAndNewSegments, failure) => {
          logFailure(s"${pathDistributor.head}: Failed to do putAssignedKeyValues, Reverting and deleting written ${targetSegmentAndNewSegments.size} segments", failure)
          targetSegmentAndNewSegments foreach {
            case (_, newSegments) =>
              newSegments foreach {
                segment =>
                  IO(segment.delete) onLeftSideEffect {
                    exception =>
                      logger.error(s"{}: Failed to delete Segment '{}' in recovery for putAssignedKeyValues", pathDistributor.head, segment.path, exception)
                  }
              }
          }
        }
    )

  def buildNewMapEntry(newSegments: Iterable[Segment],
                       originalSegmentMayBe: SegmentOption = Segment.Null,
                       initialMapEntry: Option[MapEntry[Slice[Byte], Segment]]): IO[swaydb.Error.Level, MapEntry[Slice[Byte], Segment]] = {
    import keyOrder._

    var removeOriginalSegments = true

    val nextLogEntry =
      newSegments.foldLeft(initialMapEntry) {
        case (logEntry, newSegment) =>
          //if one of the new segments have the same minKey as the original segment, remove is not required as 'put' will replace old key.
          if (removeOriginalSegments && originalSegmentMayBe.existsS(_.minKey equiv newSegment.minKey))
            removeOriginalSegments = false

          val nextLogEntry = MapEntry.Put(newSegment.minKey, newSegment)
          logEntry.map(_ ++ nextLogEntry) orElse Some(nextLogEntry)
      }

    val entryWithRemove: Option[MapEntry[Slice[Byte], Segment]] =
      originalSegmentMayBe match {
        case originalMap: Segment if removeOriginalSegments =>
          val removeLogEntry = MapEntry.Remove[Slice[Byte]](originalMap.minKey)
          nextLogEntry.map(_ ++ removeLogEntry) orElse Some(removeLogEntry)

        case _ =>
          nextLogEntry
      }

    entryWithRemove match {
      case Some(value) =>
        IO.Right(value)

      case None =>
        IO.failed("Failed to build map entry")
    }
  }

  def getFromThisLevel(key: Slice[Byte], readState: ThreadReadState): KeyValueOption =
    appendix
      .skipList
      .floor(key)
      .flatMapSomeS(Memory.Null: KeyValueOption)(_.get(key, readState))

  def getFromNextLevel(key: Slice[Byte],
                       readState: ThreadReadState): KeyValue.PutOption =
    nextLevel match {
      case Some(nextLevel) =>
        nextLevel.get(key, readState)

      case None =>
        KeyValue.Put.Null
    }

  override def get(key: Slice[Byte], readState: ThreadReadState): KeyValue.PutOption =
    Get(key, readState)

  private def mightContainKeyInThisLevel(key: Slice[Byte]): Boolean =
    appendix
      .skipList
      .floor(key)
      .existsS(_.mightContainKey(key))

  private def mightContainFunctionInThisLevel(functionId: Slice[Byte]): Boolean =
    appendix
      .skipList
      .values()
      .asScala
      .exists {
        segment =>
          segment
            .minMaxFunctionId
            .exists {
              minMax =>
                MinMax.contains(
                  key = functionId,
                  minMax = minMax
                )(FunctionStore.order)
            }
      }

  override def mightContainKey(key: Slice[Byte]): Boolean =
    mightContainKeyInThisLevel(key) ||
      nextLevel.exists(_.mightContainKey(key))

  override def mightContainFunction(functionId: Slice[Byte]): Boolean =
    mightContainFunctionInThisLevel(functionId) ||
      nextLevel.exists(_.mightContainFunction(functionId))

  private def lowerInThisLevel(key: Slice[Byte],
                               readState: ThreadReadState): LevelSeek[KeyValue] =
    appendix
      .skipList
      .lower(key)
      .flatMapSomeS(LevelSeek.None: LevelSeek[KeyValue]) {
        segment =>
          LevelSeek(
            segmentId = segment.segmentId,
            result = segment.lower(key, readState).toOptional
          )
      }

  private def lowerFromNextLevel(key: Slice[Byte],
                                 readState: ThreadReadState): KeyValue.PutOption =
    nextLevel match {
      case Some(nextLevel) =>
        nextLevel.lower(key, readState)

      case None =>
        KeyValue.Put.Null
    }

  override def floor(key: Slice[Byte],
                     readState: ThreadReadState): KeyValue.PutOption =
    get(key, readState) orElse lower(key, readState)

  override def lower(key: Slice[Byte],
                     readState: ThreadReadState): KeyValue.PutOption =
    Lower(
      key = key,
      readState = readState,
      currentSeek = Seek.Current.readStart,
      nextSeek = Seek.Next.Read
    )

  private def higherFromFloorSegment(key: Slice[Byte],
                                     readState: ThreadReadState): LevelSeek[KeyValue] =
    appendix.skipList.floor(key) match {
      case segment: Segment =>
        LevelSeek(
          segmentId = segment.segmentId,
          result = segment.higher(key, readState).toOptional
        )

      case Segment.Null =>
        LevelSeek.None
    }

  private def higherFromHigherSegment(key: Slice[Byte], readState: ThreadReadState): LevelSeek[KeyValue] =
    appendix.skipList.higher(key) match {
      case segment: Segment =>
        LevelSeek(
          segmentId = segment.segmentId,
          result = segment.higher(key, readState).toOptional
        )

      case Segment.Null =>
        LevelSeek.None
    }

  private[core] def higherInThisLevel(key: Slice[Byte], readState: ThreadReadState): LevelSeek[KeyValue] = {
    val fromFloor = higherFromFloorSegment(key, readState)

    if (fromFloor.isDefined)
      fromFloor
    else
      higherFromHigherSegment(key, readState)
  }

  private def higherInNextLevel(key: Slice[Byte],
                                readState: ThreadReadState): KeyValue.PutOption =
    nextLevel match {
      case Some(nextLevel) =>
        nextLevel.higher(key, readState)

      case None =>
        KeyValue.Put.Null
    }

  def ceiling(key: Slice[Byte],
              readState: ThreadReadState): KeyValue.PutOption =
    get(key, readState) orElse higher(key, readState)

  override def higher(key: Slice[Byte],
                      readState: ThreadReadState): KeyValue.PutOption =
    Higher(
      key = key,
      readState = readState,
      currentSeek = Seek.Current.readStart,
      nextSeek = Seek.Next.Read
    )

  /**
   * Does a quick appendix lookup.
   * It does not check if the returned key is removed. Use [[Level.head]] instead.
   */
  override def headKey(readState: ThreadReadState): SliceOption[Byte] =
    nextLevel match {
      case Some(nextLevel) =>
        val thisLevelHeadKey = appendix.skipList.headKey
        val nextLevelHeadKey = nextLevel.headKey(readState)

        MinMax.minFavourLeftC[SliceOption[Byte], Slice[Byte]](
          left = thisLevelHeadKey,
          right = nextLevelHeadKey
        )(keyOrder)

      case None =>
        appendix.skipList.headKey
    }

  /**
   * Does a quick appendix lookup.
   * It does not check if the returned key is removed. Use [[Level.last]] instead.
   */
  override def lastKey(readState: ThreadReadState): SliceOption[Byte] =
    nextLevel match {
      case Some(nextLevel) =>
        val thisLevelLastKey = appendix.skipList.last().flatMapSomeS(Slice.Null: SliceOption[Byte])(_.maxKey.maxKey)
        val nextLevelLastKey = nextLevel.lastKey(readState)

        MinMax.maxFavourLeftC[SliceOption[Byte], Slice[Byte]](
          left = thisLevelLastKey,
          right = nextLevelLastKey
        )(keyOrder)

      case None =>
        appendix.skipList.last().flatMapSomeS(Slice.Null: SliceOption[Byte])(_.maxKey.maxKey)
    }

  override def head(readState: ThreadReadState): KeyValue.PutOption =
    headKey(readState)
      .flatMapSomeC(KeyValue.Put.Null: KeyValue.PutOption) {
        firstKey =>
          ceiling(firstKey, readState)
      }

  override def last(readState: ThreadReadState): KeyValue.PutOption =
    lastKey(readState)
      .flatMapSomeC(KeyValue.Put.Null: KeyValue.PutOption) {
        lastKey =>
          floor(lastKey, readState)
      }

  def containsSegmentWithMinKey(minKey: Slice[Byte]): Boolean =
    appendix
      .skipList
      .contains(minKey)

  override def keyValueCount: Int = {
    val countFromThisLevel =
      appendix
        .skipList
        .foldLeft(0) {
          case (currentTotal, (_, segment)) =>
            currentTotal + segment.getKeyValueCount()
        }

    val countFromNextLevel =
      nextLevel match {
        case Some(nextLevel) =>
          nextLevel.keyValueCount

        case None =>
          0
      }

    countFromThisLevel + countFromNextLevel
  }

  def getSegment(minKey: Slice[Byte]): SegmentOption =
    appendix
      .skipList
      .get(minKey)

  override def segmentsCount(): Int =
    appendix
      .skipList
      .count()

  override def take(count: Int): Slice[Segment] =
    appendix
      .skipList
      .take(count)

  def isEmpty: Boolean =
    appendix.isEmpty

  def segmentFilesOnDisk: Seq[Path] =
    Effect.segmentFilesOnDisk(dirs.map(_.path))

  def segmentFilesInAppendix: Int =
    appendix
      .skipList
      .count()

  def foreachSegment[T](f: (Slice[Byte], Segment) => T): Unit =
    appendix
      .skipList
      .foreach(f)

  def segmentsInLevel(): Iterable[Segment] =
    appendix
      .skipList
      .values()
      .asScala

  def hasNextLevel: Boolean =
    nextLevel.isDefined

  override def existsOnDisk =
    dirs.forall(_.path.exists)

  override def levelSize: Long =
    appendix
      .skipList
      .foldLeft(0)(_ + _._2.segmentSize)

  override def sizeOfSegments: Long =
    levelSize + nextLevel.map(_.levelSize).getOrElse(0L)

  def segmentCountAndLevelSize: (Int, Long) =
    appendix
      .skipList
      .foldLeft((0: Int, 0L: Long)) {
        case ((segments, size), (_, segment)) =>
          (segments + 1, size + segment.segmentSize)
      }

  def meterFor(levelNumber: Int): Option[LevelMeter] =
    if (levelNumber == pathDistributor.head.path.folderId)
      Some(meter)
    else
      nextLevel.flatMap(_.meterFor(levelNumber))

  override def takeSegments(size: Int, condition: Segment => Boolean): Iterable[Segment] =
    appendix
      .skipList
      .values()
      .asScala
      .filter(condition)
      .take(size)

  override def takeLargeSegments(size: Int): Iterable[Segment] =
    appendix
      .skipList
      .values()
      .asScala
      .filter(_.segmentSize > minSegmentSize)
      .take(size)

  override def takeSmallSegments(size: Int): Iterable[Segment] =
    appendix
      .skipList
      .values()
      .asScala
      .filter(Level.isSmallSegment(_, minSegmentSize))
      .take(size)

  override def optimalSegmentsPushForward(take: Int): (Iterable[Segment], Iterable[Segment]) =
    nextLevel match {
      case Some(nextLevel) =>
        Level.optimalSegmentsToPushForward(
          level = self,
          nextLevel = nextLevel,
          take = take
        )

      case None =>
        Level.emptySegmentsToPush
    }

  override def optimalSegmentsToCollapse(take: Int): Iterable[Segment] =
    Level.optimalSegmentsToCollapse(
      level = self,
      take = take
    )

  def hasSmallSegments: Boolean =
    appendix
      .skipList
      .values()
      .asScala
      .exists(Level.isSmallSegment(_, minSegmentSize))

  def shouldSelfCompactOrExpire: Boolean =
    segmentsInLevel()
      .exists {
        segment =>
          Level.shouldCollapse(self, segment) ||
            FiniteDurations.getNearestDeadline(
              deadline = None,
              next = segment.nearestPutDeadline
            ).exists(_.isOverdue())
      }

  def hasKeyValuesToExpire: Boolean =
    Segment
      .getNearestDeadlineSegment(segmentsInLevel())
      .isSomeS

  def close: IO[swaydb.Error.Close, Unit] =
    nextLevel
      .map(_.close)
      .getOrElse(IO.unit)
      .flatMap {
        _ =>
          IO(appendix.close())
            .onLeftSideEffect {
              failure =>
                logger.error("{}: Failed to close appendix", pathDistributor.head, failure)
            }

          closeSegments()
            .onLeftSideEffect {
              failure =>
                logger.error("{}: Failed to close segments", pathDistributor.head, failure)
            }

          releaseLocks
            .onLeftSideEffect {
              failure =>
                logger.error("{}: Failed to release locks", pathDistributor.head, failure)
            }
      }

  def closeSegments(): IO[swaydb.Error.Level, Unit] = {
    segmentsInLevel()
      .foreachIO(segment => IO(segment.close), failFast = false)
      .foreach {
        failure =>
          logger.error("{}: Failed to close Segment file.", pathDistributor.head, failure.exception)
      }

    nextLevel match {
      case Some(nextLevel) =>
        nextLevel.closeSegments()

      case None =>
        IO.unit
    }
  }

  override val isTrash: Boolean =
    false

  override def isZero: Boolean =
    false

  def lastSegmentId: Option[Long] =
    appendix
      .skipList
      .last()
      .mapS(_.segmentId)

  override def stateId: Long =
    segmentIDGenerator.currentId

  override def nextCompactionDelay: FiniteDuration =
    throttle(meter).pushDelay

  override def nextThrottlePushCount: Int =
    throttle(meter).segmentsToPush

  override def delete: IO[swaydb.Error.Delete, Unit] =
    Level.delete(self)

  override def minSegmentSize: Int =
    segmentConfig.minSize
}
