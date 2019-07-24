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

package swaydb.core.segment.merge

import com.typesafe.scalalogging.LazyLogging
import swaydb.IO
import swaydb.IO._
import swaydb.core.data.KeyValue.ReadOnly
import swaydb.core.data.{Memory, Persistent, Value, _}
import swaydb.core.function.FunctionStore
import swaydb.core.group.compression.data.KeyValueGroupingStrategyInternal
import swaydb.core.merge.{FixedMerger, ValueMerger}
import swaydb.core.queue.KeyValueLimiter
import swaydb.core.segment.format.a.block._
import swaydb.data.io.Core
import swaydb.data.io.Core.Error.Private.ErrorHandler
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

private[core] object SegmentMerger extends LazyLogging {
  implicit val keyValueLimiter = KeyValueLimiter.none

  /**
    * If the last Segment is too small, this function merges the last Segment with the previous Segment's key-value.
    *
    * It also executes grouping on the last un-grouped key-values if compression type (grouping) is provided.
    */
  @tailrec
  def completeMerge(segments: ListBuffer[ListBuffer[Transient]],
                    minSegmentSize: Long,
                    forMemory: Boolean,
                    groupLastSegment: Boolean,
                    createdInLevel: Int,
                    valuesConfig: ValuesBlock.Config,
                    sortedIndexConfig: SortedIndexBlock.Config,
                    binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                    hashIndexConfig: HashIndexBlock.Config,
                    bloomFilterConfig: BloomFilterBlock.Config)(implicit groupingStrategy: Option[KeyValueGroupingStrategyInternal]): IO[Core.Error.Private, ListBuffer[ListBuffer[Transient]]] = {
    //if there are any small Segments, merge them into previous Segment.
    val noSmallSegments =
      if (segments.length >= 2 && ((forMemory && segments.last.lastOption.map(_.stats.memorySegmentSize).getOrElse(0) < minSegmentSize) || segments.last.lastOption.map(_.stats.segmentSize).getOrElse(0) < minSegmentSize)) {
        val newSegments = segments dropRight 1
        val newSegmentsLast = newSegments.last
        val newSegmentsLastKeyValue = newSegmentsLast.last
        segments.last foreach {
          keyValue =>
            newSegmentsLast +=
              keyValue.updatePrevious(
                valuesConfig = newSegmentsLastKeyValue.valuesConfig,
                sortedIndexConfig = newSegmentsLastKeyValue.sortedIndexConfig,
                binarySearchIndexConfig = newSegmentsLastKeyValue.binarySearchIndexConfig,
                hashIndexConfig = newSegmentsLastKeyValue.hashIndexConfig,
                bloomFilterConfig = newSegmentsLastKeyValue.bloomFilterConfig,
                previous = newSegmentsLast.lastOption
              )
        }
        newSegments
      } else {
        segments
      }

    //if compression is specified, compress any last non grouped key-values and try completingMerge again
    //in-case compression of the last Segment's key-values resulted is a smaller Segment.
    groupingStrategy match {
      case Some(groupingS) if groupLastSegment =>
        noSmallSegments.filter(_.nonEmpty).lastOption match {
          case Some(lastSegmentsKeyValues) =>
            SegmentGrouper.group(
              segmentKeyValues = lastSegmentsKeyValues,
              groupingStrategy = groupingS,
              valuesConfig = valuesConfig,
              sortedIndexConfig = sortedIndexConfig,
              createdInLevel = createdInLevel,
              binarySearchIndexConfig = binarySearchIndexConfig,
              hashIndexConfig = hashIndexConfig,
              bloomFilterConfig = bloomFilterConfig,
              force = true
            ) match {
              case IO.Success(Some(_)) => //grouping occurred.
                //do completeMerge again in-case grouping the last Segment resulted in a smaller Segment.
                completeMerge(
                  segments = noSmallSegments,
                  minSegmentSize = minSegmentSize,
                  forMemory = forMemory,
                  groupLastSegment = false,
                  createdInLevel = createdInLevel,
                  valuesConfig = valuesConfig,
                  sortedIndexConfig = sortedIndexConfig,
                  binarySearchIndexConfig = binarySearchIndexConfig,
                  hashIndexConfig = hashIndexConfig,
                  bloomFilterConfig = bloomFilterConfig
                )

              case IO.Success(None) =>
                IO.Success(noSmallSegments.filter(_.nonEmpty))

              case IO.Failure(error) =>
                IO.Failure(error)
            }
          case None =>
            IO.Success(noSmallSegments.filter(_.nonEmpty))
        }
      case _ =>
        IO.Success(noSmallSegments.filter(_.nonEmpty))
    }
  }

  def split(keyValues: Iterable[KeyValue.ReadOnly],
            minSegmentSize: Long,
            isLastLevel: Boolean,
            forInMemory: Boolean,
            createdInLevel: Int,
            valuesConfig: ValuesBlock.Config,
            sortedIndexConfig: SortedIndexBlock.Config,
            binarySearchIndexConfig: BinarySearchIndexBlock.Config,
            hashIndexConfig: HashIndexBlock.Config,
            bloomFilterConfig: BloomFilterBlock.Config,
            segmentIO: SegmentIO)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                  groupingStrategy: Option[KeyValueGroupingStrategyInternal]): IO[Core.Error.Private, Iterable[Iterable[Transient]]] = {
    val splits = ListBuffer[ListBuffer[Transient]](ListBuffer())
    keyValues foreachIO {
      keyValue =>
        SegmentGrouper.addKeyValue(
          keyValueToAdd = keyValue,
          splits = splits,
          minSegmentSize = minSegmentSize,
          forInMemory = forInMemory,
          isLastLevel = isLastLevel,
          createdInLevel= createdInLevel,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentIO = segmentIO
        )
    } match {
      case None =>
        completeMerge(
          segments = splits,
          minSegmentSize = minSegmentSize,
          forMemory = forInMemory,
          createdInLevel = createdInLevel,
          groupLastSegment = true,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig
        )

      case Some(IO.Failure(failure)) =>
        IO.Failure(failure)
    }
  }

  /**
    * TODO: Both inputs are Memory so temporarily it's OK to call .find because Memory key-values do not do IO. But this should be fixed and .find should not be invoked.
    *
    * Need a type class implementation on executing side effects of merging key-values, one for [[Memory]] key-values and other for [[Persistent]] key-value types.
    */
  def merge(newKeyValues: Slice[Memory.SegmentResponse],
            oldKeyValues: Slice[Memory.SegmentResponse])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                         timeOrder: TimeOrder[Slice[Byte]],
                                                         functionStore: FunctionStore): ListBuffer[Transient.SegmentResponse] =
    merge(
      newKeyValues = newKeyValues,
      oldKeyValues = oldKeyValues,
      minSegmentSize = Int.MaxValue,
      isLastLevel = false,
      forInMemory = true,
      createdInLevel= 0,
      valuesConfig = ValuesBlock.Config.disabled,
      sortedIndexConfig = SortedIndexBlock.Config.disabled,
      binarySearchIndexConfig = BinarySearchIndexBlock.Config.disabled,
      hashIndexConfig = HashIndexBlock.Config.disabled,
      bloomFilterConfig = BloomFilterBlock.Config.disabled,
      segmentIO = SegmentIO.defaultSynchronisedStoredIfCompressed
    )(keyOrder, timeOrder, functionStore, None)
      .get
      .flatten
      .asInstanceOf[ListBuffer[Transient.SegmentResponse]]

  def merge(newKeyValue: Memory.SegmentResponse,
            oldKeyValue: Memory.SegmentResponse)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                 timeOrder: TimeOrder[Slice[Byte]],
                                                 functionStore: FunctionStore): ListBuffer[Transient.SegmentResponse] =
    merge(
      newKeyValues = Slice(newKeyValue),
      oldKeyValues = Slice(oldKeyValue),
      minSegmentSize = Int.MaxValue,
      isLastLevel = false,
      forInMemory = true,
      createdInLevel = 0,
      valuesConfig = ValuesBlock.Config.disabled,
      sortedIndexConfig = SortedIndexBlock.Config.disabled,
      binarySearchIndexConfig = BinarySearchIndexBlock.Config.disabled,
      hashIndexConfig = HashIndexBlock.Config.disabled,
      bloomFilterConfig = BloomFilterBlock.Config.disabled,
      segmentIO = SegmentIO.defaultSynchronisedStoredIfCompressed
    )(keyOrder, timeOrder, functionStore, None)
      .get
      .flatten
      .asInstanceOf[ListBuffer[Transient.SegmentResponse]]

  def merge(newKeyValues: Slice[KeyValue.ReadOnly],
            oldKeyValues: Slice[KeyValue.ReadOnly],
            minSegmentSize: Long,
            isLastLevel: Boolean,
            forInMemory: Boolean,
            createdInLevel: Int,
            valuesConfig: ValuesBlock.Config,
            sortedIndexConfig: SortedIndexBlock.Config,
            binarySearchIndexConfig: BinarySearchIndexBlock.Config,
            hashIndexConfig: HashIndexBlock.Config,
            bloomFilterConfig: BloomFilterBlock.Config,
            segmentIO: SegmentIO)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                  timeOrder: TimeOrder[Slice[Byte]],
                                  functionStore: FunctionStore,
                                  groupingStrategy: Option[KeyValueGroupingStrategyInternal]): IO[Core.Error.Private, Iterable[Iterable[Transient]]] =
    merge(
      newKeyValues = MergeList(newKeyValues),
      oldKeyValues = MergeList(oldKeyValues),
      splits = ListBuffer[ListBuffer[Transient]](ListBuffer.empty),
      minSegmentSize = minSegmentSize,
      isLastLevel = isLastLevel,
      forInMemory = forInMemory,
      valuesConfig = valuesConfig,
      createdInLevel = createdInLevel,
      sortedIndexConfig = sortedIndexConfig,
      binarySearchIndexConfig = binarySearchIndexConfig,
      hashIndexConfig = hashIndexConfig,
      bloomFilterConfig = bloomFilterConfig,
      segmentIO = segmentIO
    ) flatMap {
      splits =>
        completeMerge(
          segments = splits,
          minSegmentSize = minSegmentSize,
          forMemory = forInMemory,
          createdInLevel = createdInLevel,
          groupLastSegment = true,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig
        )
    }

  private def merge(newKeyValues: MergeList[Memory.Range, KeyValue.ReadOnly],
                    oldKeyValues: MergeList[Memory.Range, KeyValue.ReadOnly],
                    splits: ListBuffer[ListBuffer[Transient]],
                    minSegmentSize: Long,
                    isLastLevel: Boolean,
                    forInMemory: Boolean,
                    createdInLevel: Int,
                    valuesConfig: ValuesBlock.Config,
                    sortedIndexConfig: SortedIndexBlock.Config,
                    binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                    hashIndexConfig: HashIndexBlock.Config,
                    bloomFilterConfig: BloomFilterBlock.Config,
                    segmentIO: SegmentIO)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                          timeOrder: TimeOrder[Slice[Byte]],
                                          functionStore: FunctionStore,
                                          groupingStrategy: Option[KeyValueGroupingStrategyInternal]): IO[Core.Error.Private, ListBuffer[ListBuffer[Transient]]] = {

    import keyOrder._

    implicit val groupIO = groupingStrategy.map(_.groupIO) getOrElse segmentIO

    def add(nextKeyValue: KeyValue.ReadOnly): IO[Core.Error.Private, Unit] =
      SegmentGrouper.addKeyValue(
        keyValueToAdd = nextKeyValue,
        splits = splits,
        minSegmentSize = minSegmentSize,
        forInMemory = forInMemory,
        isLastLevel = isLastLevel,
        createdInLevel = createdInLevel,
        valuesConfig = valuesConfig,
        sortedIndexConfig = sortedIndexConfig,
        binarySearchIndexConfig = binarySearchIndexConfig,
        hashIndexConfig = hashIndexConfig,
        bloomFilterConfig = bloomFilterConfig,
        segmentIO = segmentIO
      )

    @tailrec
    def doMerge(newKeyValues: MergeList[Memory.Range, KeyValue.ReadOnly],
                oldKeyValues: MergeList[Memory.Range, KeyValue.ReadOnly]): IO[Core.Error.Private, ListBuffer[ListBuffer[Transient]]] =
      (newKeyValues.headOption, oldKeyValues.headOption) match {

        case (Some(newKeyValue: KeyValue.ReadOnly.Fixed), Some(oldKeyValue: KeyValue.ReadOnly.Fixed)) =>
          if (oldKeyValue.key < newKeyValue.key)
            add(oldKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (newKeyValue.key < oldKeyValue.key)
            add(newKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else
            FixedMerger(
              newKeyValue = newKeyValue,
              oldKeyValue = oldKeyValue
            ) match {
              case IO.Success(mergedKeyValue) =>
                add(mergedKeyValue) match {
                  case IO.Success(_) =>
                    doMerge(newKeyValues.dropHead(), oldKeyValues.dropHead())

                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is an overwrite key-value and the existing is a range key-value.
          */
        case (Some(newKeyValue: KeyValue.ReadOnly.Fixed), Some(oldRangeKeyValue: ReadOnly.Range)) =>
          if (newKeyValue.key < oldRangeKeyValue.fromKey)
            add(newKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (newKeyValue.key >= oldRangeKeyValue.toKey)
            add(oldRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //is in-range key
            oldRangeKeyValue.fetchFromAndRangeValue match {
              case IO.Success((oldFromValue, oldRangeRangeValue)) if newKeyValue.key equiv oldRangeKeyValue.fromKey =>
                FixedMerger(
                  newKeyValue = newKeyValue,
                  oldKeyValue = oldFromValue.getOrElse(oldRangeRangeValue).toMemory(newKeyValue.key)
                ).flatMap(_.toFromValue()) match {
                  case IO.Success(newFromValue) =>
                    val toPrepend =
                      Memory.Range(
                        fromKey = oldRangeKeyValue.fromKey,
                        toKey = oldRangeKeyValue.toKey,
                        fromValue = Some(newFromValue),
                        rangeValue = oldRangeRangeValue
                      )
                    doMerge(newKeyValues.dropHead(), oldKeyValues.dropPrepend(toPrepend))

                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Success((oldFromValue, oldRangeValue)) => //else it's a mid range value - split required.
                FixedMerger(
                  newKeyValue = newKeyValue,
                  oldKeyValue = oldRangeValue.toMemory(newKeyValue.key)
                ).flatMap(_.toFromValue()) match {
                  case IO.Success(newFromValue) =>
                    val lowerSplit = Memory.Range(oldRangeKeyValue.fromKey, newKeyValue.key, oldFromValue, oldRangeValue)
                    val upperSplit = Memory.Range(newKeyValue.key, oldRangeKeyValue.toKey, Some(newFromValue), oldRangeValue)
                    add(lowerSplit) match {
                      case IO.Success(_) =>
                        doMerge(newKeyValues.dropHead(), oldKeyValues.dropPrepend(upperSplit))

                      case IO.Failure(error) =>
                        IO.Failure(error)
                    }
                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is a range and the existing is a fixed key-value.
          */
        case (Some(newRangeKeyValue: ReadOnly.Range), Some(oldKeyValue: KeyValue.ReadOnly.Fixed)) =>
          if (oldKeyValue.key >= newRangeKeyValue.toKey)
            add(newRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldKeyValue.key < newRangeKeyValue.fromKey)
            add(oldKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //is in-range key
            newRangeKeyValue.fetchFromAndRangeValue match {
              case IO.Success((newRangeFromValue, newRangeRangeValue)) if newRangeKeyValue.fromKey equiv oldKeyValue.key =>
                val fromOrRange = newRangeFromValue.getOrElse(newRangeRangeValue)
                fromOrRange match {
                  //the range is remove or put simply drop old key-value. No need to merge! Important! do a time check.
                  case Value.Remove(None, _) | _: Value.Put if fromOrRange.time > oldKeyValue.time =>
                    doMerge(newKeyValues, oldKeyValues.dropHead())

                  case _ =>
                    //if not then do a merge.
                    FixedMerger(
                      newKeyValue = newRangeFromValue.getOrElse(newRangeRangeValue).toMemory(oldKeyValue.key),
                      oldKeyValue = oldKeyValue
                    ).flatMap(_.toFromValue()) match {
                      case IO.Success(newFromValue) =>
                        val newKeyValue =
                          Memory.Range(
                            fromKey = newRangeKeyValue.fromKey,
                            toKey = newRangeKeyValue.toKey,
                            fromValue = Some(newFromValue),
                            rangeValue = newRangeRangeValue
                          )
                        doMerge(newKeyValues.dropPrepend(newKeyValue), oldKeyValues.dropHead())

                      case IO.Failure(error) =>
                        IO.Failure(error)
                    }
                }

              case IO.Success((newRangeFromValue, newRangeRangeValue)) => //split required.
                newRangeRangeValue match {
                  //the range is remove or put simply remove all old key-values. No need to merge! Important! do a time check.
                  case Value.Remove(None, rangeTime) if rangeTime > oldKeyValue.time =>
                    doMerge(newKeyValues, oldKeyValues.dropHead())

                  case _ =>
                    FixedMerger(
                      newKeyValue = newRangeRangeValue.toMemory(oldKeyValue.key),
                      oldKeyValue = oldKeyValue
                    ).flatMap(_.toFromValue()) match {
                      case IO.Success(newFromValue) =>
                        val lowerSplit = Memory.Range(newRangeKeyValue.fromKey, oldKeyValue.key, newRangeFromValue, newRangeRangeValue)
                        val upperSplit = Memory.Range(oldKeyValue.key, newRangeKeyValue.toKey, Some(newFromValue), newRangeRangeValue)
                        add(lowerSplit) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropPrepend(upperSplit), oldKeyValues.dropHead())
                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }

                      case IO.Failure(error) =>
                        IO.Failure(error)
                    }
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When both the key-values are ranges.
          */
        case (Some(newRangeKeyValue: ReadOnly.Range), Some(oldRangeKeyValue: ReadOnly.Range)) =>
          if (newRangeKeyValue.toKey <= oldRangeKeyValue.fromKey)
            add(newRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }

          else if (oldRangeKeyValue.toKey <= newRangeKeyValue.fromKey)
            add(oldRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else
            newRangeKeyValue.fetchFromAndRangeValue match {
              case IO.Success((newRangeFromValue, newRangeRangeValue)) =>
                oldRangeKeyValue.fetchFromAndRangeValue match {
                  case IO.Success((oldRangeFromValue, oldRangeRangeValue)) =>
                    val newRangeFromKey = newRangeKeyValue.fromKey
                    val newRangeToKey = newRangeKeyValue.toKey
                    val oldRangeFromKey = oldRangeKeyValue.fromKey
                    val oldRangeToKey = oldRangeKeyValue.toKey

                    if (newRangeFromKey < oldRangeFromKey) {
                      //1   -     15
                      //      10   -  20
                      if (newRangeToKey < oldRangeToKey) {
                        val upperSplit = Memory.Range(newRangeFromKey, oldRangeFromKey, newRangeFromValue, newRangeRangeValue)
                        val middleSplit =
                          Memory.Range(
                            fromKey = oldRangeFromKey,
                            toKey = newRangeToKey,
                            fromValue = oldRangeFromValue.map(ValueMerger(oldRangeFromKey, newRangeRangeValue, _).get),
                            rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                          )
                        val lowerSplit = Memory.Range(newRangeToKey, oldRangeToKey, None, oldRangeRangeValue)

                        add(upperSplit).flatMap(_ => add(middleSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropPrepend(lowerSplit))

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else if (newRangeToKey equiv oldRangeToKey) {
                        //1      -      20
                        //      10   -  20
                        val upperSplit = Memory.Range(newRangeFromKey, oldRangeFromKey, newRangeFromValue, newRangeRangeValue)

                        val lowerSplit =
                          Memory.Range(
                            fromKey = oldRangeFromKey,
                            toKey = oldRangeToKey,
                            fromValue = oldRangeFromValue.map(ValueMerger(oldRangeFromKey, newRangeRangeValue, _).get),
                            rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                          )

                        add(upperSplit).flatMap(_ => add(lowerSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else {
                        //1      -         21
                        //      10   -  20
                        val upperSplit = Memory.Range(newRangeFromKey, oldRangeFromKey, newRangeFromValue, newRangeRangeValue)
                        val middleSplit =
                          Memory.Range(
                            fromKey = oldRangeFromKey,
                            toKey = oldRangeToKey,
                            fromValue = oldRangeFromValue.map(ValueMerger(oldRangeFromKey, newRangeRangeValue, _).get),
                            rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                          )

                        val lowerSplit = Memory.Range(oldRangeToKey, newRangeToKey, None, newRangeRangeValue)

                        add(upperSplit).flatMap(_ => add(middleSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropPrepend(lowerSplit), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      }
                    } else if (newRangeFromKey equiv oldRangeFromKey) {
                      //      10 - 15
                      //      10   -  20
                      if (newRangeToKey < oldRangeToKey) {
                        val upperSplit = Memory.Range(
                          fromKey = newRangeFromKey,
                          toKey = newRangeToKey,
                          fromValue =
                            oldRangeFromValue.map(ValueMerger(newRangeFromKey, newRangeFromValue.getOrElse(newRangeRangeValue), _).get) orElse {
                              newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get)
                            },
                          rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                        )
                        val lowerSplit = Memory.Range(newRangeToKey, oldRangeToKey, None, oldRangeRangeValue)

                        add(upperSplit) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropPrepend(lowerSplit))

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else if (newRangeToKey equiv oldRangeToKey) {
                        //      10   -  20
                        //      10   -  20
                        val update = Memory.Range(
                          fromKey = newRangeFromKey,
                          toKey = newRangeToKey,
                          fromValue =
                            oldRangeFromValue.map(ValueMerger(newRangeFromKey, newRangeFromValue.getOrElse(newRangeRangeValue), _).get) orElse {
                              newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get)
                            },
                          rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                        )

                        add(update) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else {
                        //      10   -     21
                        //      10   -  20
                        val upperSplit = Memory.Range(
                          fromKey = newRangeFromKey,
                          toKey = oldRangeToKey,
                          fromValue =
                            oldRangeFromValue.map(ValueMerger(newRangeFromKey, newRangeFromValue.getOrElse(newRangeRangeValue), _).get) orElse {
                              newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get)
                            },
                          rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                        )
                        val lowerSplit = Memory.Range(oldRangeToKey, newRangeToKey, None, newRangeRangeValue)

                        add(upperSplit) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropPrepend(lowerSplit), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      }
                    } else {
                      //        11 - 15
                      //      10   -   20
                      if (newRangeToKey < oldRangeToKey) {
                        val upperSplit = Memory.Range(oldRangeFromKey, newRangeFromKey, oldRangeFromValue, oldRangeRangeValue)

                        val middleSplit =
                          Memory.Range(
                            fromKey = newRangeFromKey,
                            toKey = newRangeToKey,
                            fromValue = newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get),
                            rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                          )

                        val lowerSplit = Memory.Range(newRangeToKey, oldRangeToKey, None, oldRangeRangeValue)

                        add(upperSplit).flatMap(_ => add(middleSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropPrepend(lowerSplit))

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else if (newRangeToKey equiv oldRangeToKey) {
                        //        11 -   20
                        //      10   -   20
                        val upperSplit = Memory.Range(oldRangeFromKey, newRangeFromKey, oldRangeFromValue, oldRangeRangeValue)

                        val lowerSplit = Memory.Range(
                          fromKey = newRangeFromKey,
                          toKey = newRangeToKey,
                          fromValue = newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get),
                          rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                        )

                        add(upperSplit).flatMap(_ => add(lowerSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropHead(), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      } else {
                        //        11 -     21
                        //      10   -   20
                        val upperSplit = Memory.Range(oldRangeFromKey, newRangeFromKey, oldRangeFromValue, oldRangeRangeValue)

                        val middleSplit =
                          Memory.Range(
                            fromKey = newRangeFromKey,
                            toKey = oldRangeToKey,
                            fromValue = newRangeFromValue.map(ValueMerger(newRangeFromKey, _, oldRangeRangeValue).get),
                            rangeValue = ValueMerger(newRangeRangeValue, oldRangeRangeValue).get
                          )

                        val lowerSplit = Memory.Range(oldRangeToKey, newRangeToKey, None, newRangeRangeValue)

                        add(upperSplit).flatMap(_ => add(middleSplit)) match {
                          case IO.Success(_) =>
                            doMerge(newKeyValues.dropPrepend(lowerSplit), oldKeyValues.dropHead())

                          case IO.Failure(error) =>
                            IO.Failure(error)
                        }
                      }
                    }

                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is a Fixed key-value and the existing is a Group key-value.
          */
        case (Some(newKeyValue: KeyValue.ReadOnly.Fixed), Some(oldGroupKeyValue: ReadOnly.Group)) =>
          if (newKeyValue.key < oldGroupKeyValue.minKey)
            add(newKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldGroupKeyValue.maxKey lessThan newKeyValue.key)
            add(oldGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //is in-group key. Open the Group and merge.
            oldGroupKeyValue.segment.getAll() match {
              case IO.Success(oldGroupKeyValues) =>
                doMerge(
                  newKeyValues,
                  MergeList[Memory.Range, KeyValue.ReadOnly](oldGroupKeyValues) append oldKeyValues.dropHead()
                )

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is a Group and the existing is a Fixed key-value.
          */
        case (Some(newGroupKeyValue: ReadOnly.Group), Some(oldKeyValue: KeyValue.ReadOnly.Fixed)) =>
          if (newGroupKeyValue.maxKey lessThan oldKeyValue.key)
            add(newGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldKeyValue.key < newGroupKeyValue.key)
            add(oldKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //Group overlaps fixed key-value
            newGroupKeyValue.segment.getAll() match {
              case IO.Success(newGroupKeyValues) =>
                doMerge(
                  MergeList[Memory.Range, KeyValue.ReadOnly](newGroupKeyValues) append newKeyValues.dropHead(),
                  oldKeyValues
                )

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is a Range key-value and the existing is a Group key-value.
          */
        case (Some(newRangeKeyValue: KeyValue.ReadOnly.Range), Some(oldGroupKeyValue: ReadOnly.Group)) =>
          if (newRangeKeyValue.toKey <= oldGroupKeyValue.minKey)
            add(newRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldGroupKeyValue.maxKey lessThan newRangeKeyValue.fromKey)
            add(oldGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else
          //Open the Group and merge.
            newRangeKeyValue.fetchFromAndRangeValue match {
              //Cases when the Remove range completely overlaps the group and there is no time set for
              //both fromValue & RangeValue then there is no need to open the group. Simply remove the Group.
              case IO.Success((None | Some(Value.Remove(None, Time.empty)), Value.Remove(None, Time.empty))) if newRangeKeyValue.fromKey <= oldGroupKeyValue.minKey && oldGroupKeyValue.maxKey.maxKey < newRangeKeyValue.toKey =>
                doMerge(newKeyValues, oldKeyValues.dropHead())

              case IO.Success(_) =>
                oldGroupKeyValue.segment.getAll() match {
                  case IO.Success(oldGroupKeyValues) =>
                    doMerge(
                      newKeyValues,
                      MergeList[Memory.Range, KeyValue.ReadOnly](oldGroupKeyValues) append oldKeyValues.dropHead()
                    )

                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When the input is a Group and the existing is a Range key-value.
          */
        case (Some(newGroupKeyValue: ReadOnly.Group), Some(oldRangeKeyValue: KeyValue.ReadOnly.Range)) =>
          if (newGroupKeyValue.maxKey lessThan oldRangeKeyValue.fromKey)
            add(newGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldRangeKeyValue.toKey <= newGroupKeyValue.minKey)
            add(oldRangeKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //Group overlaps fixed key-value
            newGroupKeyValue.segment.getAll() match {
              case IO.Success(newGroupKeyValues) =>
                doMerge(
                  MergeList[Memory.Range, KeyValue.ReadOnly](newGroupKeyValues) append newKeyValues.dropHead(),
                  oldKeyValues
                )

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        /**
          * When both are Groups.
          */
        case (Some(newGroupKeyValue: ReadOnly.Group), Some(oldGroupKeyValue: KeyValue.ReadOnly.Group)) =>
          if (newGroupKeyValue.maxKey lessThan oldGroupKeyValue.minKey)
            add(newGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues.dropHead(), oldKeyValues)
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else if (oldGroupKeyValue.maxKey lessThan newGroupKeyValue.minKey)
            add(oldGroupKeyValue) match {
              case IO.Success(_) =>
                doMerge(newKeyValues, oldKeyValues.dropHead())
              case IO.Failure(error) =>
                IO.Failure(error)
            }
          else //Group overlaps fixed key-value
            newGroupKeyValue.segment.getAll() match {
              case IO.Success(newGroupKeyValues) =>
                oldGroupKeyValue.segment.getAll() match {
                  case IO.Success(oldGroupKeyValues) =>
                    doMerge(
                      MergeList[Memory.Range, KeyValue.ReadOnly](newGroupKeyValues) append newKeyValues.dropHead(),
                      MergeList[Memory.Range, KeyValue.ReadOnly](oldGroupKeyValues) append oldKeyValues.dropHead()
                    )

                  case IO.Failure(error) =>
                    IO.Failure(error)
                }

              case IO.Failure(error) =>
                IO.Failure(error)
            }

        //there are no more oldKeyValues. Add all remaining newKeyValues
        case (Some(_), None) =>
          newKeyValues.foreachIO(add) match {
            case Some(IO.Failure(error)) =>
              IO.Failure(error)
            case None =>
              IO.Success(splits)
          }

        //there are no more newKeyValues. Add all remaining oldKeyValues
        case (None, Some(_)) =>
          oldKeyValues.foreachIO(add) match {
            case Some(IO.Failure(error)) =>
              IO.Failure(error)
            case None =>
              IO.Success(splits)
          }

        case (None, None) =>
          IO.Success(splits)
      }

    IO.CatchLeak(doMerge(newKeyValues, oldKeyValues))
  }
}
