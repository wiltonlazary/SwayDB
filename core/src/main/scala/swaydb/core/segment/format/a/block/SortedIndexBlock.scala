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

package swaydb.core.segment.format.a.block

import com.typesafe.scalalogging.LazyLogging
import swaydb.Error.Segment.ErrorHandler
import swaydb.IO
import swaydb.IO._
import swaydb.compression.CompressionInternal
import swaydb.core.cache.Cache
import swaydb.core.data.{KeyValue, Persistent, Transient}
import swaydb.core.io.reader.Reader
import swaydb.core.segment.format.a.block.reader.UnblockedReader
import swaydb.core.segment.format.a.entry.reader.EntryReader
import swaydb.core.util.{Bytes, FunctionUtil}
import swaydb.data.config.{IOAction, IOStrategy, UncompressedBlockInfo}
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.data.util.ByteSizeOf

import scala.annotation.tailrec

private[core] object SortedIndexBlock extends LazyLogging {

  val blockName = this.getClass.getSimpleName.dropRight(1)

  implicit object SortedIndexBlockOps extends BlockOps[SortedIndexBlock.Offset, SortedIndexBlock] {
    override def updateBlockOffset(block: SortedIndexBlock, start: Int, size: Int): SortedIndexBlock =
      block.copy(offset = createOffset(start = start, size = size))

    override def createOffset(start: Int, size: Int): Offset =
      SortedIndexBlock.Offset(start = start, size = size)

    override def readBlock(header: Block.Header[Offset]): IO[swaydb.Error.Segment, SortedIndexBlock] =
      SortedIndexBlock.read(header)
  }

  object Config {
    val disabled =
      Config(
        blockIO = (dataType: IOAction) => IOStrategy.SynchronisedIO(cacheOnAccess = dataType.isCompressed),
        enableAccessPositionIndex = false,
        prefixCompressionResetCount = 0,
        normaliseIndex = false,
        compressions = (_: UncompressedBlockInfo) => Seq.empty
      )

    def apply(config: swaydb.data.config.SortedKeyIndex): Config =
      config match {
        case config: swaydb.data.config.SortedKeyIndex.Enable =>
          apply(config)
      }

    def apply(enable: swaydb.data.config.SortedKeyIndex.Enable): Config =
      Config(
        enableAccessPositionIndex = enable.enablePositionIndex,
        prefixCompressionResetCount = enable.prefixCompression.resetCount max 0,
        normaliseIndex = enable.prefixCompression.resetCount <= 0 && enable.prefixCompression.normaliseIndexForBinarySearch,
        blockIO = FunctionUtil.safe(IOStrategy.synchronisedStoredIfCompressed, enable.ioStrategy),
        compressions =
          FunctionUtil.safe(
            default = (_: UncompressedBlockInfo) => Seq.empty[CompressionInternal],
            function = enable.compressions(_: UncompressedBlockInfo) map CompressionInternal.apply
          )
      )

    def apply(blockIO: IOAction => IOStrategy,
              prefixCompressionResetCount: Int,
              enableAccessPositionIndex: Boolean,
              normaliseIndex: Boolean,
              compressions: UncompressedBlockInfo => Seq[CompressionInternal]): Config =
      new Config(
        blockIO = blockIO,
        prefixCompressionResetCount = prefixCompressionResetCount max 0,
        enableAccessPositionIndex = enableAccessPositionIndex,
        normaliseIndex = prefixCompressionResetCount <= 0 && normaliseIndex,
        compressions = compressions
      )
  }

  class Config private(val blockIO: IOAction => IOStrategy,
                       val prefixCompressionResetCount: Int,
                       val enableAccessPositionIndex: Boolean,
                       val normaliseIndex: Boolean,
                       val compressions: UncompressedBlockInfo => Seq[CompressionInternal])

  case class Offset(start: Int, size: Int) extends BlockOffset

  case class State(var _bytes: Slice[Byte],
                   headerSize: Int,
                   hasPrefixCompression: Boolean,
                   enableAccessPositionIndex: Boolean,
                   normaliseIndex: Boolean,
                   isPreNormalised: Boolean,
                   segmentMaxIndexEntrySize: Int,
                   compressions: UncompressedBlockInfo => Seq[CompressionInternal]) {
    def bytes = _bytes

    def bytes_=(bytes: Slice[Byte]) =
      this._bytes = bytes
  }

  val hasCompressionHeaderSize = {
    val size =
      Block.headerSize(true) +
        ByteSizeOf.boolean + //enablePositionIndex
        ByteSizeOf.boolean + //normalisedForBinarySearch
        ByteSizeOf.boolean + //hasPrefixCompression
        ByteSizeOf.boolean + //isPreNormalised
        ByteSizeOf.varInt // segmentMaxSortedIndexEntrySize

    Bytes.sizeOf(size) + size
  }

  val noCompressionHeaderSize = {
    val size =
      Block.headerSize(false) +
        ByteSizeOf.boolean + //enablePositionIndex
        ByteSizeOf.boolean + //normalisedForBinarySearch
        ByteSizeOf.boolean + //hasPrefixCompression
        ByteSizeOf.boolean + //isPreNormalised
        ByteSizeOf.varInt // segmentMaxSortedIndexEntrySize

    Bytes.sizeOf(size) + size
  }

  def headerSize(hasCompression: Boolean): Int =
    if (hasCompression)
      hasCompressionHeaderSize
    else
      noCompressionHeaderSize

  def init(keyValues: Iterable[Transient]): (SortedIndexBlock.State, Iterable[Transient]) = {
    val isPreNormalised = keyValues.last.stats.hasSameIndexSizes()

    val normalisedKeyValues =
      if (!isPreNormalised && keyValues.last.sortedIndexConfig.normaliseIndex)
        Transient.normalise(keyValues)
      else
        keyValues

    val hasCompression =
      normalisedKeyValues
        .last
        .sortedIndexConfig
        .compressions(UncompressedBlockInfo(normalisedKeyValues.last.stats.segmentSortedIndexSize))
        .nonEmpty

    val headSize = headerSize(hasCompression)

    val bytes =
      if (hasCompression) //stats calculate size with no compression if there is compression add remaining bytes.
        Slice.create[Byte](normalisedKeyValues.last.stats.segmentSortedIndexSize - noCompressionHeaderSize + headSize)
      else
        Slice.create[Byte](normalisedKeyValues.last.stats.segmentSortedIndexSize)

    bytes moveWritePosition headSize

    (
      State(
        _bytes = bytes,
        headerSize = headSize,
        isPreNormalised = isPreNormalised,
        hasPrefixCompression = normalisedKeyValues.last.stats.hasPrefixCompression,
        enableAccessPositionIndex = normalisedKeyValues.last.sortedIndexConfig.enableAccessPositionIndex,
        normaliseIndex = normalisedKeyValues.last.sortedIndexConfig.normaliseIndex,
        segmentMaxIndexEntrySize = normalisedKeyValues.last.stats.segmentMaxSortedIndexEntrySize,
        compressions =
          //cannot have no compression to begin with a then have compression because that upsets the total bytes required.
          if (hasCompression)
            normalisedKeyValues.last.sortedIndexConfig.compressions
          else
            _ => Seq.empty
      ),
      normalisedKeyValues
    )
  }

  def write(keyValue: Transient, state: SortedIndexBlock.State): IO[swaydb.Error.Segment, Unit] =
    IO {
      state.bytes addAll keyValue.indexEntryBytes
    }

  def close(state: State): IO[swaydb.Error.Segment, State] =
    Block.block(
      headerSize = state.headerSize,
      bytes = state.bytes,
      compressions = state.compressions(UncompressedBlockInfo(state.bytes.size)),
      blockName = blockName
    ) flatMap {
      compressedOrUncompressedBytes =>
        state.bytes = compressedOrUncompressedBytes
        state.bytes addBoolean state.enableAccessPositionIndex
        state.bytes addBoolean state.hasPrefixCompression
        state.bytes addBoolean state.normaliseIndex
        state.bytes addBoolean state.isPreNormalised
        state.bytes addIntUnsigned state.segmentMaxIndexEntrySize
        if (state.bytes.currentWritePosition > state.headerSize)
          IO.Failure(swaydb.Error.Fatal(s"Calculated header size was incorrect. Expected: ${state.headerSize}. Used: ${state.bytes.currentWritePosition - 1}"))
        else
          IO.Success(state)
    }

  def read(header: Block.Header[SortedIndexBlock.Offset]): IO[swaydb.Error.Segment, SortedIndexBlock] =
    for {
      enableAccessPositionIndex <- header.headerReader.readBoolean()
      hasPrefixCompression <- header.headerReader.readBoolean()
      normaliseForBinarySearch <- header.headerReader.readBoolean()
      isPreNormalised <- header.headerReader.readBoolean()
      segmentMaxIndexEntrySize <- header.headerReader.readIntUnsigned()
    } yield {
      SortedIndexBlock(
        offset = header.offset,
        enableAccessPositionIndex = enableAccessPositionIndex,
        hasPrefixCompression = hasPrefixCompression,
        normaliseForBinarySearch = normaliseForBinarySearch,
        segmentMaxIndexEntrySize = segmentMaxIndexEntrySize,
        isPreNormalised = isPreNormalised,
        headerSize = header.headerSize,
        compressionInfo = header.compressionInfo
      )
    }

  private def readNextKeyValue(previous: Persistent,
                               indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, Persistent] =
    readNextKeyValue(
      indexEntrySizeMayBe = Some(previous.nextIndexSize),
      indexReader = indexReader moveTo previous.nextIndexOffset,
      valuesReader = valuesReader,
      previous = Some(previous)
    )

  private def readNextKeyValue(fromPosition: Int,
                               indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, Persistent] =
    readNextKeyValue(
      indexEntrySizeMayBe = None,
      indexReader = indexReader moveTo fromPosition,
      valuesReader = valuesReader,
      previous = None
    )

  //Pre-requisite: The position of the index on the reader should be set.
  private def readNextKeyValue(indexEntrySizeMayBe: Option[Int],
                               indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]],
                               previous: Option[Persistent]): IO[swaydb.Error.Segment, Persistent] =
    try {
      //println("readNextKeyValue")
      val positionBeforeRead = indexReader.getPosition
      //size of the index entry to read
      val indexSize =
        indexEntrySizeMayBe match {
          case Some(indexEntrySize) =>
            indexReader skip Bytes.sizeOf(indexEntrySize)
            indexEntrySize

          case None =>
            indexReader.readIntUnsigned().get
        }

      //5 extra bytes are read for each entry to fetch the next index's size.
      val bytesToRead = indexSize + ByteSizeOf.varInt

      //read all bytes for this index entry plus the next 5 bytes to fetch next index entry's size.
      val indexEntryBytesAndNextIndexEntrySize = (indexReader read bytesToRead).get

      val extraBytesRead = indexEntryBytesAndNextIndexEntrySize.size - indexSize

      //The above fetches another 5 bytes (unsigned int) along with previous index entry.
      //These 5 bytes contains the next index's size. Here the next key-values indexSize and indexOffset are read.
      val (nextIndexSize, nextIndexOffset) =
      if (extraBytesRead <= 0) {
        //no next key-value, next size is 0 and set offset to -1.
        (0, -1)
      } else {
        //if extra tail byte were read this mean that this index has a next key-value.
        //next indexEntrySize is only read if it's required.
        indexEntryBytesAndNextIndexEntrySize
          .drop(indexSize)
          .readIntUnsigned()
          .map {
            nextIndexEntrySize =>
              (nextIndexEntrySize, indexReader.getPosition - extraBytesRead)
          }.get
      }

      //if they entries were normalised then deNormalise the indexEntry and pass it to the EntryReader for parsing.
      val deNormalisedIndexEntryBytes =
        if (!indexReader.block.isPreNormalised && indexReader.block.normaliseForBinarySearch)
          Bytes.deNormalise(indexEntryBytesAndNextIndexEntrySize.take(indexSize))
        else
          indexEntryBytesAndNextIndexEntrySize.take(indexSize)

      val sortedIndexReader = Reader[swaydb.Error.Segment](deNormalisedIndexEntryBytes)

      //create value cache reader given the value offset.
      //todo pass in blockIO config when read values.
      def valueCache =
        valuesReader map {
          valuesReader =>
            Cache.concurrentIO[swaydb.Error.Segment, ValuesBlock.Offset, UnblockedReader[ValuesBlock.Offset, ValuesBlock]](synchronised = false, stored = false, initial = None) {
              offset =>
                if (offset.size == 0)
                  ValuesBlock.emptyUnblockedIO
                else
                  IO(UnblockedReader.moveTo(offset, valuesReader))
            }
        }

      EntryReader.read(
        //take only the bytes required for this in entry and submit it for parsing/reading.
        indexReader = sortedIndexReader,
        mightBeCompressed = indexReader.block.hasPrefixCompression,
        valueCache = valueCache,
        indexOffset = positionBeforeRead,
        nextIndexOffset = nextIndexOffset,
        nextIndexSize = nextIndexSize,
        hasAccessPositionIndex = indexReader.block.enableAccessPositionIndex,
        previous = previous
      )
    } catch {
      case exception: Exception =>
        IO.failed(exception)
    }

  def readAll(keyValueCount: Int,
              sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
              valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]],
              addTo: Option[Slice[KeyValue.ReadOnly]] = None): IO[swaydb.Error.Segment, Slice[KeyValue.ReadOnly]] =
    try {
      sortedIndexReader moveTo 0
      val readSortedIndexReader = sortedIndexReader.readAllAndGetReader().get

      val entries = addTo getOrElse Slice.create[Persistent](keyValueCount)
      (1 to keyValueCount).foldLeftIO(Option.empty[Persistent]) {
        case (previousMayBe, _) =>
          val nextIndexSize =
            previousMayBe map {
              previous =>
                //If previous is known, keep reading same reader
                // and set the next position of the reader to be of the next index's offset.
                readSortedIndexReader moveTo previous.nextIndexOffset
                previous.nextIndexSize
            }

          readNextKeyValue(
            indexEntrySizeMayBe = nextIndexSize,
            indexReader = readSortedIndexReader,
            valuesReader = valuesReader,
            previous = previousMayBe
          ) map {
            next =>
              entries add next
              Some(next)
          }
      } map (_ => entries)
    } catch {
      case exception: Exception =>
        IO.failed(exception)
    }

  def search(key: Slice[Byte],
             startFrom: Option[Persistent],
             sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
             valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit order: KeyOrder[Slice[Byte]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    if (startFrom.exists(from => order.gt(from.key, key))) //TODO - to be removed via macros. this is for internal use only. Detects that a higher startFrom key does not get passed to this.
      IO.Failure(swaydb.Error.Fatal("startFrom key is greater than target key."))
    else
      search(
        matcher = KeyMatcher.Get(key),
        startFrom = startFrom,
        indexReader = sortedIndexReader,
        valuesReader = valuesReader
      )

  def searchSeekOne(key: Slice[Byte],
                    start: Persistent,
                    indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                    valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit order: KeyOrder[Slice[Byte]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    if (order.gt(start.key, key)) //TODO - to be removed via macros. this is for internal use only. Detects that a higher startFrom key does not get passed to this.
      IO.Failure(swaydb.Error.Fatal("startFrom key is greater than target key."))
    else
      search(
        matcher = KeyMatcher.Get.SeekOne(key),
        startFrom = Some(start),
        indexReader = indexReader,
        valuesReader = valuesReader
      )

  def searchHigher(key: Slice[Byte],
                   startFrom: Option[Persistent],
                   sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                   valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit order: KeyOrder[Slice[Byte]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    if (startFrom.exists(from => order.gt(from.key, key))) //TODO - to be removed via macros. this is for internal use only. Detects that a higher startFrom key does not get passed to this.
      IO.Failure(swaydb.Error.Fatal("startFrom key is greater than target key."))
    else
      search(
        matcher = KeyMatcher.Higher(key),
        startFrom = startFrom,
        indexReader = sortedIndexReader,
        valuesReader = valuesReader
      )

  def searchHigherSeekOne(key: Slice[Byte],
                          startFrom: Persistent,
                          sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                          valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit order: KeyOrder[Slice[Byte]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    if (order.gt(startFrom.key, key)) //TODO - to be removed via macros. this is for internal use only. Detects that a higher startFrom key does not get passed to this.
      IO.Failure(swaydb.Error.Fatal("startFrom key is greater than target key."))
    else
      search(
        matcher = KeyMatcher.Higher.SeekOne(key),
        startFrom = Some(startFrom),
        indexReader = sortedIndexReader,
        valuesReader = valuesReader
      )

  def searchLower(key: Slice[Byte],
                  startFrom: Option[Persistent],
                  sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                  valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit order: KeyOrder[Slice[Byte]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    if (startFrom.exists(from => order.gt(from.key, key))) //TODO - to be removed via macros. this is for internal use only. Detects that a higher startFrom key does not get passed to this.
      IO.Failure(swaydb.Error.Fatal("startFrom key is greater than target key."))
    else
      search(
        matcher = KeyMatcher.Lower(key),
        startFrom = startFrom,
        indexReader = sortedIndexReader,
        valuesReader = valuesReader
      )

  private def search(matcher: KeyMatcher,
                     startFrom: Option[Persistent],
                     indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                     valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    startFrom match {
      case Some(startFrom) =>
        matchOrNextAndPersistent(
          previous = startFrom,
          next = None,
          matcher = matcher,
          indexReader = indexReader,
          valuesReader = valuesReader
        )

      //No start from. Get the first index entry from the File and start from there.
      case None =>
        readNextKeyValue(
          fromPosition = 0,
          indexReader = indexReader,
          valuesReader = valuesReader
        ) flatMap {
          keyValue =>
            matchOrNextAndPersistent(
              previous = keyValue,
              next = None,
              matcher = matcher,
              indexReader = indexReader,
              valuesReader = valuesReader
            )
        }
    }

  def findAndMatchOrNextPersistent(matcher: KeyMatcher,
                                   fromOffset: Int,
                                   indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                                   valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    readNextKeyValue(
      fromPosition = fromOffset,
      indexReader = indexReader,
      valuesReader = valuesReader
    ) flatMap {
      persistent =>
        //println("matchOrNextAndPersistent")
        matchOrNextAndPersistent(
          previous = persistent,
          next = None,
          matcher = matcher,
          indexReader = indexReader,
          valuesReader = valuesReader
        )
    }

  def findAndMatchOrNextMatch(matcher: KeyMatcher,
                              fromOffset: Int,
                              sortedIndex: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                              valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, KeyMatcher.Result] =
    readNextKeyValue(
      fromPosition = fromOffset,
      indexReader = sortedIndex,
      valuesReader = valuesReader
    ) flatMap {
      persistent =>
        matchOrNext(
          previous = persistent,
          next = None,
          matcher = matcher,
          indexReader = sortedIndex,
          valuesReader = valuesReader
        )
    }

  @tailrec
  def matchOrNext(previous: Persistent,
                  next: Option[Persistent],
                  matcher: KeyMatcher,
                  indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                  valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, KeyMatcher.Result.Complete] =
    matcher(
      previous = previous,
      next = next,
      hasMore = hasMore(next getOrElse previous)
    ) match {
      case KeyMatcher.Result.BehindFetchNext(previousKeyValue) =>
        //        assert(previous.key.readInt() <= previousKeyValue.key.readInt())
        val readFrom = next getOrElse previousKeyValue
        readNextKeyValue(
          previous = readFrom,
          indexReader = indexReader,
          valuesReader = valuesReader
        ) match {
          case IO.Success(nextNextKeyValue) =>
            matchOrNext(
              previous = readFrom,
              next = Some(nextNextKeyValue),
              matcher = matcher,
              indexReader = indexReader,
              valuesReader = valuesReader
            )

          case IO.Failure(error) =>
            IO.Failure(error)
        }

      case result: KeyMatcher.Result.Complete =>
        result.asIO
    }

  def matchOrNextAndPersistent(previous: Persistent,
                               next: Option[Persistent],
                               matcher: KeyMatcher,
                               indexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]]): IO[swaydb.Error.Segment, Option[Persistent]] =
    matchOrNext(
      previous = previous,
      next = next,
      matcher = matcher,
      indexReader = indexReader,
      valuesReader = valuesReader
    ) match {
      case IO.Success(KeyMatcher.Result.Matched(_, keyValue, _)) =>
        IO.Success(Some(keyValue))

      case IO.Success(KeyMatcher.Result.AheadOrNoneOrEnd | _: KeyMatcher.Result.BehindStopped) =>
        IO.none

      case IO.Failure(error) =>
        IO.Failure(error)
    }

  private def hasMore(keyValue: Persistent) =
    keyValue.nextIndexSize > 0
}

private[core] case class SortedIndexBlock(offset: SortedIndexBlock.Offset,
                                          enableAccessPositionIndex: Boolean,
                                          hasPrefixCompression: Boolean,
                                          normaliseForBinarySearch: Boolean,
                                          isPreNormalised: Boolean,
                                          headerSize: Int,
                                          segmentMaxIndexEntrySize: Int,
                                          compressionInfo: Option[Block.CompressionInfo]) extends Block[SortedIndexBlock.Offset]
