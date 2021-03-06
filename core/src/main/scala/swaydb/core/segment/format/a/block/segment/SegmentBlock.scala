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

package swaydb.core.segment.format.a.block.segment

import com.typesafe.scalalogging.LazyLogging
import swaydb.Compression
import swaydb.compression.CompressionInternal
import swaydb.core.data.Memory
import swaydb.core.segment.format.a.block._
import swaydb.core.segment.format.a.block.binarysearch.BinarySearchIndexBlock
import swaydb.core.segment.format.a.block.bloomfilter.BloomFilterBlock
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock
import swaydb.core.segment.format.a.block.segment.data.{ClosedBlocks, ClosedBlocksWithFooter, TransientSegment}
import swaydb.core.segment.format.a.block.segment.footer.SegmentFooterBlock
import swaydb.core.segment.format.a.block.sortedindex.SortedIndexBlock
import swaydb.core.segment.format.a.block.values.ValuesBlock
import swaydb.core.segment.merge.MergeStats
import swaydb.core.segment.merge.MergeStats.Persistent
import swaydb.core.segment.{PersistentSegmentMany, PersistentSegmentOne}
import swaydb.core.util.{Bytes, Collections}
import swaydb.data.config.{IOAction, IOStrategy, SegmentConfig, UncompressedBlockInfo}
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.data.util.ByteSizeOf
import swaydb.data.util.StorageUnits._

import scala.collection.mutable.ListBuffer
import scala.util.Try

private[core] object SegmentBlock extends LazyLogging {

  val blockName = this.getClass.getSimpleName.dropRight(1)

  val formatId: Byte = 1.toByte

  val crcBytes: Int = 13

  object Config {

    def default =
      new Config(
        ioStrategy = {
          case IOAction.OpenResource =>
            //cache so that files are kept in-memory
            IOStrategy.ConcurrentIO(cacheOnAccess = true)

          case IOAction.ReadDataOverview =>
            //cache so that block overview like footer and blockInfos are kept in memory.
            IOStrategy.ConcurrentIO(cacheOnAccess = true)

          case data: IOAction.DataAction =>
            //cache only if the data is compressed.
            IOStrategy.ConcurrentIO(cacheOnAccess = data.isCompressed)
        },
        cacheBlocksOnCreate = true,
        minSize = 2.mb,
        maxCount = 200000,
        pushForward = true,
        mmapWrites = false,
        mmapReads = false,
        deleteEventually = true,
        compressions = _ => Seq.empty
      )

    def apply(config: SegmentConfig): SegmentBlock.Config =
      apply(
        ioStrategy = config.ioStrategy,
        cacheBlocksOnCreate = config.cacheSegmentBlocksOnCreate,
        minSize = config.minSegmentSize,
        maxCount = config.maxKeyValuesPerSegmentGroup,
        pushForward = config.pushForward,
        mmapWrites = config.mmap.mmapWrite,
        mmapReads = config.mmap.mmapRead,
        deleteEventually = config.deleteSegmentsEventually,
        compressions = config.compression
      )

    def apply(ioStrategy: IOAction => IOStrategy,
              cacheBlocksOnCreate: Boolean,
              minSize: Int,
              maxCount: Int,
              pushForward: Boolean,
              mmapWrites: Boolean,
              mmapReads: Boolean,
              deleteEventually: Boolean,
              compressions: UncompressedBlockInfo => Iterable[Compression]): Config =
      applyInternal(
        ioStrategy = ioStrategy,
        cacheBlocksOnCreate = cacheBlocksOnCreate,
        minSize = minSize,
        maxCount = maxCount,
        pushForward = pushForward,
        mmapWrites = mmapWrites,
        mmapReads = mmapReads,
        deleteEventually = deleteEventually,
        compressions =
          uncompressedBlockInfo =>
            Try(compressions(uncompressedBlockInfo))
              .getOrElse(Seq.empty)
              .map(CompressionInternal.apply)
              .toSeq
      )

    private[core] def applyInternal(ioStrategy: IOAction => IOStrategy,
                                    cacheBlocksOnCreate: Boolean,
                                    minSize: Int,
                                    maxCount: Int,
                                    pushForward: Boolean,
                                    mmapWrites: Boolean,
                                    mmapReads: Boolean,
                                    deleteEventually: Boolean,
                                    compressions: UncompressedBlockInfo => Seq[CompressionInternal]): Config =
      new Config(
        ioStrategy = ioStrategy,
        cacheBlocksOnCreate = cacheBlocksOnCreate,
        minSize = minSize max 1,
        maxCount = maxCount max 1,
        pushForward = pushForward,
        mmapWrites = mmapWrites,
        mmapReads = mmapReads,
        deleteEventually = deleteEventually,
        compressions = compressions
      )
  }

  class Config private(val ioStrategy: IOAction => IOStrategy,
                       val cacheBlocksOnCreate: Boolean,
                       val minSize: Int,
                       val maxCount: Int,
                       val pushForward: Boolean,
                       val mmapWrites: Boolean,
                       val mmapReads: Boolean,
                       val deleteEventually: Boolean,
                       val compressions: UncompressedBlockInfo => Seq[CompressionInternal]) {

    def copy(minSize: Int = minSize, maxCount: Int = maxCount): SegmentBlock.Config =
      SegmentBlock.Config.applyInternal(
        ioStrategy = ioStrategy,
        cacheBlocksOnCreate = cacheBlocksOnCreate,
        minSize = minSize,
        maxCount = maxCount,
        pushForward = pushForward,
        mmapWrites = mmapWrites,
        mmapReads = mmapReads,
        deleteEventually = deleteEventually,
        compressions = compressions
      )
  }

  object Offset {
    def empty =
      SegmentBlock.Offset(0, 0)
  }

  case class Offset(start: Int, size: Int) extends BlockOffset

  def read(header: Block.Header[Offset]): SegmentBlock =
    SegmentBlock(
      offset = header.offset,
      headerSize = header.headerSize,
      compressionInfo = header.compressionInfo
    )

  def writeOneOrMany(mergeStats: MergeStats.Persistent.Closed[Iterable],
                     createdInLevel: Int,
                     bloomFilterConfig: BloomFilterBlock.Config,
                     hashIndexConfig: HashIndexBlock.Config,
                     binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                     sortedIndexConfig: SortedIndexBlock.Config,
                     valuesConfig: ValuesBlock.Config,
                     segmentConfig: SegmentBlock.Config)(implicit keyOrder: KeyOrder[Slice[Byte]]): Slice[TransientSegment] =
    if (mergeStats.isEmpty) {
      Slice.empty
    } else {
      val singles: Slice[TransientSegment.One] =
        writeOnes(
          mergeStats = mergeStats,
          createdInLevel = createdInLevel,
          bloomFilterConfig = bloomFilterConfig,
          hashIndexConfig = hashIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          sortedIndexConfig = sortedIndexConfig,
          valuesConfig = valuesConfig,
          segmentConfig = segmentConfig
        )

      writeOneOrMany(
        createdInLevel = createdInLevel,
        singles = singles,
        sortedIndexConfig = sortedIndexConfig,
        valuesConfig = valuesConfig,
        segmentConfig = segmentConfig
      )
    }

  protected def writeOneOrMany(createdInLevel: Int,
                               singles: Slice[TransientSegment.One],
                               sortedIndexConfig: SortedIndexBlock.Config,
                               valuesConfig: ValuesBlock.Config,
                               segmentConfig: SegmentBlock.Config)(implicit keyOrder: KeyOrder[Slice[Byte]]): Slice[TransientSegment] =
    if (singles.isEmpty) {
      Slice.empty
    } else {
      val groups: Slice[Slice[TransientSegment.One]] =
        Collections.groupedBySize[TransientSegment.One](
          minGroupSize = segmentConfig.minSize,
          itemSize = _.segmentSize,
          items = singles
        )

      groups map {
        segments =>
          if (segments.size == 1) {
            val segment = segments.head
            segment.copy(segmentBytes = PersistentSegmentOne.formatIdSliceSlice ++ segment.segmentBytes)
          } else {
            val listKeyValue: Persistent.Builder[Memory, Slice] =
              MergeStats.persistent(Slice.newBuilder(segments.size * 2))

            segments.foldLeft(0) {
              case (offset, segment) =>
                val segmentSize = segment.segmentSize
                listKeyValue addAll segment.toKeyValue(offset, segmentSize)
                offset + segmentSize
            }

            //enableAccessPosition and normalising index is current not required for list segment since no binary search is perform.
            val closedListKeyValues = listKeyValue.close(hasAccessPositionIndex = false)

            val modifiedSortedIndex =
              if (sortedIndexConfig.enableAccessPositionIndex || sortedIndexConfig.normaliseIndex)
                sortedIndexConfig.copy(enableAccessPositionIndex = false, normaliseIndex = false)
              else
                sortedIndexConfig

            val listSegments =
              writeOnes(
                mergeStats = closedListKeyValues,
                createdInLevel = createdInLevel,
                bloomFilterConfig = BloomFilterBlock.Config.disabled,
                hashIndexConfig = HashIndexBlock.Config.disabled,
                binarySearchIndexConfig = BinarySearchIndexBlock.Config.disabled,
                sortedIndexConfig = modifiedSortedIndex,
                valuesConfig = valuesConfig,
                segmentConfig = segmentConfig.copy(minSize = Int.MaxValue, maxCount = Int.MaxValue)
              )

            assert(listSegments.size == 1, s"listSegments.size: ${listSegments.size} != 1")

            val listSegment = listSegments.head
            val listSegmentSize = listSegment.segmentSize

            val headerSize =
              ByteSizeOf.byte +
                Bytes.sizeOfUnsignedInt(listSegmentSize)

            val slotsRequired = segments.foldLeft(headerSize + listSegment.segmentBytes.size)(_ + _.segmentBytes.size)

            val segmentBytes = Slice.create[Slice[Byte]](slotsRequired)

            val headerBytes = Slice.create[Byte](headerSize)
            headerBytes add PersistentSegmentMany.formatId
            headerBytes addUnsignedInt listSegmentSize

            segmentBytes add headerBytes
            segmentBytes addAll listSegment.segmentBytes
            segments foreach {
              segment =>
                segmentBytes addAll segment.segmentBytes
            }

            assert(listSegment.minMaxFunctionId.isEmpty, "minMaxFunctionId was not empty")

            TransientSegment.Many(
              minKey = segments.head.minKey,
              maxKey = segments.last.maxKey,
              headerSize = headerSize,
              //minMaxFunctionId is not stored in Many. All functionId request should be deferred
              //onto the SegmentRefs itself.
              minMaxFunctionId = None,
              nearestPutDeadline = listSegment.nearestPutDeadline,
              segments = Slice(listSegment) ++ segments,
              segmentBytes = segmentBytes
            )
          }
      }
    }

  def writeOnes(mergeStats: MergeStats.Persistent.Closed[Iterable],
                createdInLevel: Int,
                bloomFilterConfig: BloomFilterBlock.Config,
                hashIndexConfig: HashIndexBlock.Config,
                binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                sortedIndexConfig: SortedIndexBlock.Config,
                valuesConfig: ValuesBlock.Config,
                segmentConfig: SegmentBlock.Config)(implicit keyOrder: KeyOrder[Slice[Byte]]): Slice[TransientSegment.One] =
    if (mergeStats.isEmpty)
      Slice.empty
    else
      writeClosed(
        keyValues = mergeStats,
        createdInLevel = createdInLevel,
        bloomFilterConfig = bloomFilterConfig,
        hashIndexConfig = hashIndexConfig,
        binarySearchIndexConfig = binarySearchIndexConfig,
        sortedIndexConfig = sortedIndexConfig,
        valuesConfig = valuesConfig,
        segmentConfig = segmentConfig
      ) map {
        segment =>
          Block.block(
            blocks = segment,
            compressions = segmentConfig.compressions(UncompressedBlockInfo(segment.segmentSize)),
            blockName = blockName
          )
      }

  def writeClosed(keyValues: MergeStats.Persistent.Closed[Iterable],
                  createdInLevel: Int,
                  bloomFilterConfig: BloomFilterBlock.Config,
                  hashIndexConfig: HashIndexBlock.Config,
                  binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                  sortedIndexConfig: SortedIndexBlock.Config,
                  valuesConfig: ValuesBlock.Config,
                  segmentConfig: SegmentBlock.Config)(implicit keyOrder: KeyOrder[Slice[Byte]]): Slice[ClosedBlocksWithFooter] =
    if (keyValues.isEmpty) {
      Slice.empty
    } else {
      //IMPORTANT! - The following is critical for compaction performance!

      //start sortedIndex for a new Segment.
      var sortedIndex =
        SortedIndexBlock.init(
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig
        )

      //start valuesBlock for a new Segment.
      var values =
        ValuesBlock.init(
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          builder = sortedIndex.builder
        )

      val keyValuesCount = keyValues.keyValuesCount

      val totalAllocatedSize = sortedIndex.compressibleBytes.allocatedSize + values.fold(0)(_.compressibleBytes.allocatedSize)
      val maxSegmentCountBasedOnSize = (totalAllocatedSize / segmentConfig.minSize) + 1
      val maxSegmentCountBasedOnCount = (keyValuesCount / segmentConfig.maxCount) + 1
      val maxSegmentsCount = maxSegmentCountBasedOnSize max maxSegmentCountBasedOnCount
      val segments = Slice.create[ClosedBlocksWithFooter](maxSegmentsCount)

      def unwrittenTailSegmentBytes() =
        sortedIndex.compressibleBytes.unwrittenTailSize() + {
          if (values.isDefined)
            values.get.compressibleBytes.unwrittenTailSize()
          else
            0
        }

      //keys to write to bloomFilter.
      val bloomFilterIndexableKeys = ListBuffer.empty[Slice[Byte]]

      var totalProcessedCount = 0 //numbers of key-values written
      var processedInThisSegment = 0 //numbers of key-values written
      //start off with true for cases with keyValues are empty.
      //true if the following iteration exited after closing the Segment.
      var closed = true

      //start building the segment.
      keyValues.keyValues foreach {
        keyValue =>
          closed = false
          totalProcessedCount += 1
          processedInThisSegment += 1

          val comparableKey = keyOrder.comparableKey(keyValue.key)
          bloomFilterIndexableKeys += comparableKey

          SortedIndexBlock.write(keyValue = keyValue, state = sortedIndex)
          values foreach (ValuesBlock.write(keyValue, _))

          //Do not include SegmentFooterBlock.optimalBytesRequired here. Screws up the above max segments count estimation.
          var currentSegmentSize = sortedIndex.compressibleBytes.size
          values foreach (currentSegmentSize += _.compressibleBytes.size)

          //check and close segment if segment size limit is reached.
          //to do - maybe check if compression is defined and increase the segmentSize.
          def segmentSizeLimitReached: Boolean =
            currentSegmentSize >= segmentConfig.minSize && unwrittenTailSegmentBytes() > segmentConfig.minSize

          def segmentCountLimitReached: Boolean =
            processedInThisSegment >= segmentConfig.maxCount && (keyValuesCount - totalProcessedCount >= segmentConfig.maxCount)

          if (segmentCountLimitReached || segmentSizeLimitReached) {
            logger.debug(s"Creating segment of size: $currentSegmentSize.bytes. segmentCountLimitReached: $segmentCountLimitReached. segmentSizeLimitReached: $segmentSizeLimitReached")

            val (closedSegment, nextSortedIndex, nextValues) =
              writeSegmentBlock(
                createdInLevel = createdInLevel,
                hasMoreKeyValues = totalProcessedCount < keyValuesCount,
                bloomFilterIndexableKeys = bloomFilterIndexableKeys,
                sortedIndex = sortedIndex,
                values = values,
                bloomFilterConfig = bloomFilterConfig,
                hashIndexConfig = hashIndexConfig,
                binarySearchIndexConfig = binarySearchIndexConfig,
                sortedIndexConfig = sortedIndexConfig,
                valuesConfig = valuesConfig,
                prepareForCachingSegmentBlocksOnCreate = segmentConfig.cacheBlocksOnCreate
              )

            segments add closedSegment

            //segment's closed. Prepare for next Segment.
            bloomFilterIndexableKeys.clear() //clear bloomFilter keys.

            nextSortedIndex foreach { //set the newSortedIndex if it was created.
              newSortedIndex =>
                sortedIndex = newSortedIndex
            }

            values = nextValues
            processedInThisSegment = 0
            closed = true
          }
      }

      //if the segment was closed and all key-values were written then close Segment.
      if (closed) {
        segments
      } else {
        val (closedSegment, nextSortedIndex, nextValuesBlock) =
          writeSegmentBlock(
            createdInLevel = createdInLevel,
            hasMoreKeyValues = false,
            bloomFilterIndexableKeys = bloomFilterIndexableKeys,
            sortedIndex = sortedIndex,
            values = values,
            bloomFilterConfig = bloomFilterConfig,
            hashIndexConfig = hashIndexConfig,
            binarySearchIndexConfig = binarySearchIndexConfig,
            sortedIndexConfig = sortedIndexConfig,
            valuesConfig = valuesConfig,
            prepareForCachingSegmentBlocksOnCreate = segmentConfig.cacheBlocksOnCreate
          )

        //temporary check.
        assert(nextSortedIndex.isEmpty && nextValuesBlock.isEmpty, s"${nextSortedIndex.isEmpty} && ${nextValuesBlock.isEmpty} is not empty.")

        segments add closedSegment
      }
    }

  private def writeSegmentBlock(createdInLevel: Int,
                                hasMoreKeyValues: Boolean,
                                bloomFilterIndexableKeys: ListBuffer[Slice[Byte]],
                                sortedIndex: SortedIndexBlock.State,
                                values: Option[ValuesBlock.State],
                                bloomFilterConfig: BloomFilterBlock.Config,
                                hashIndexConfig: HashIndexBlock.Config,
                                binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                                sortedIndexConfig: SortedIndexBlock.Config,
                                valuesConfig: ValuesBlock.Config,
                                prepareForCachingSegmentBlocksOnCreate: Boolean): (ClosedBlocksWithFooter, Option[SortedIndexBlock.State], Option[ValuesBlock.State]) = {
    //tail bytes before closing and compression is applied.
    val unwrittenTailSortedIndexBytes = sortedIndex.compressibleBytes.unwrittenTail()
    val unwrittenTailValueBytes = values.map(_.compressibleBytes.unwrittenTail())

    val closedBlocks =
      closeBlocks(
        sortedIndex = sortedIndex,
        values = values,
        bloomFilterIndexableKeys = bloomFilterIndexableKeys,
        bloomFilterConfig = bloomFilterConfig,
        hashIndexConfig = hashIndexConfig,
        binarySearchIndexConfig = binarySearchIndexConfig,
        prepareForCachingSegmentBlocksOnCreate = prepareForCachingSegmentBlocksOnCreate
      )

    val footer =
      SegmentFooterBlock.init(
        keyValuesCount = closedBlocks.sortedIndex.entriesCount,
        rangesCount = closedBlocks.sortedIndex.rangeCount,
        hasPut = closedBlocks.sortedIndex.hasPut,
        createdInLevel = createdInLevel
      )

    val closedFooter: SegmentFooterBlock.State =
      SegmentFooterBlock.writeAndClose(
        state = footer,
        closedBlocks = closedBlocks
      )

    val ref =
      new ClosedBlocksWithFooter(
        minKey = closedBlocks.sortedIndex.minKey,
        maxKey = closedBlocks.sortedIndex.maxKey,

        functionMinMax = closedBlocks.minMaxFunction,

        nearestDeadline = closedBlocks.nearestDeadline,
        valuesBlockHeader = closedBlocks.values.map(_.header.close()),

        valuesBlock = closedBlocks.values.map(_.compressibleBytes.close()),
        valuesUnblockedReader = closedBlocks.valuesUnblockedReader,

        sortedIndexBlockHeader = closedBlocks.sortedIndex.header.close(),
        sortedIndexBlock = closedBlocks.sortedIndex.compressibleBytes.close(),
        sortedIndexUnblockedReader = closedBlocks.sortedIndexUnblockedReader,
        sortedIndexClosedState = closedBlocks.sortedIndex,

        hashIndexBlockHeader = closedBlocks.hashIndex map (_.header.close()),
        hashIndexBlock = closedBlocks.hashIndex map (_.compressibleBytes.close()),
        hashIndexUnblockedReader = closedBlocks.hashIndexUnblockedReader,

        binarySearchIndexBlockHeader = closedBlocks.binarySearchIndex map (_.header.close()),
        binarySearchIndexBlock = closedBlocks.binarySearchIndex map (_.compressibleBytes.close()),
        binarySearchUnblockedReader = closedBlocks.binarySearchUnblockedReader,

        bloomFilterBlockHeader = closedBlocks.bloomFilter map (_.header.close()),
        bloomFilterBlock = closedBlocks.bloomFilter map (_.compressibleBytes.close()),
        bloomFilterUnblockedReader = closedBlocks.bloomFilterUnblockedReader,

        footerBlock = closedFooter.bytes.close()
      )

    //start new sortedIndex block only if there are more key-values to process
    val newSortedIndex =
      if (hasMoreKeyValues)
        Some(
          SortedIndexBlock.init(
            bytes = unwrittenTailSortedIndexBytes,
            compressDuplicateValues = valuesConfig.compressDuplicateValues,
            compressDuplicateRangeValues = valuesConfig.compressDuplicateRangeValues,
            sortedIndexConfig = sortedIndexConfig
          )
        )
      else
        None

    //start new values block only if there are more key-values to process
    val newValues =
      if (hasMoreKeyValues)
        newSortedIndex flatMap {
          newSortedIndex =>
            unwrittenTailValueBytes map {
              tailValueBytes =>
                ValuesBlock.init(
                  bytes = tailValueBytes,
                  valuesConfig = valuesConfig,
                  builder = newSortedIndex.builder
                )
            }
        }
      else
        None

    (ref, newSortedIndex, newValues)
  }

  private def closeBlocks(sortedIndex: SortedIndexBlock.State,
                          values: Option[ValuesBlock.State],
                          bloomFilterIndexableKeys: ListBuffer[Slice[Byte]],
                          bloomFilterConfig: BloomFilterBlock.Config,
                          hashIndexConfig: HashIndexBlock.Config,
                          binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                          prepareForCachingSegmentBlocksOnCreate: Boolean): ClosedBlocks = {
    val sortedIndexState = SortedIndexBlock.close(sortedIndex)
    val valuesState = values map ValuesBlock.close

    val bloomFilter =
      if (sortedIndexState.hasRemoveRange || bloomFilterIndexableKeys.size < bloomFilterConfig.minimumNumberOfKeys)
        None
      else
        BloomFilterBlock.init(
          numberOfKeys = bloomFilterIndexableKeys.size,
          falsePositiveRate = bloomFilterConfig.falsePositiveRate,
          updateMaxProbe = bloomFilterConfig.optimalMaxProbe,
          compressions = bloomFilterConfig.compressions
        )

    val hashIndex =
      HashIndexBlock.init(
        sortedIndexState = sortedIndexState,
        hashIndexConfig = hashIndexConfig
      )

    val binarySearchIndex =
      BinarySearchIndexBlock.init(
        sortedIndexState = sortedIndexState,
        binarySearchConfig = binarySearchIndexConfig
      )

    if (hashIndex.isDefined || binarySearchIndex.isDefined)
      sortedIndexState.secondaryIndexEntries foreach {
        indexEntry =>
          val hit =
            if (hashIndex.isDefined)
              HashIndexBlock.write(
                entry = indexEntry,
                state = hashIndex.get
              )
            else
              false

          //if it's a hit and binary search is not configured to be full.
          //no need to check if the value was previously written to binary search here since BinarySearchIndexBlock itself performs this check.
          if (binarySearchIndex.isDefined && (binarySearchIndexConfig.fullIndex || !hit))
            BinarySearchIndexBlock.write(
              entry = indexEntry,
              state = binarySearchIndex.get
            )
      }

    bloomFilter foreach {
      bloomFilter =>
        bloomFilterIndexableKeys foreach {
          key =>
            BloomFilterBlock.add(key, bloomFilter)
        }
    }

    new ClosedBlocks(
      sortedIndex = sortedIndexState,
      values = valuesState,
      hashIndex = hashIndex.flatMap(HashIndexBlock.close),
      binarySearchIndex = binarySearchIndex.flatMap(state => BinarySearchIndexBlock.close(state, sortedIndexState.uncompressedPrefixCount)),
      bloomFilter = bloomFilter.flatMap(BloomFilterBlock.close),
      minMaxFunction = sortedIndexState.minMaxFunctionId,
      prepareForCachingSegmentBlocksOnCreate = prepareForCachingSegmentBlocksOnCreate
    )
  }

  implicit object SegmentBlockOps extends BlockOps[SegmentBlock.Offset, SegmentBlock] {
    override def updateBlockOffset(block: SegmentBlock, start: Int, size: Int): SegmentBlock =
      block.copy(offset = SegmentBlock.Offset(start = start, size = size))

    override def createOffset(start: Int, size: Int): Offset =
      SegmentBlock.Offset(start, size)

    override def readBlock(header: Block.Header[SegmentBlock.Offset]): SegmentBlock =
      SegmentBlock.read(header)
  }

}

private[core] case class SegmentBlock(offset: SegmentBlock.Offset,
                                      headerSize: Int,
                                      compressionInfo: Option[Block.CompressionInfo]) extends Block[SegmentBlock.Offset]


