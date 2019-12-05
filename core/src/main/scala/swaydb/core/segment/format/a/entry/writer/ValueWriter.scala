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

package swaydb.core.segment.format.a.entry.writer

import swaydb.IO
import swaydb.core.data.Transient
import swaydb.core.segment.format.a.entry.id.{BaseEntryId, TransientToKeyValueIdBinder}
import swaydb.core.util.Bytes
import swaydb.core.util.Options._
import swaydb.data.slice.Slice

private[writer] object ValueWriter {

  def write[T](current: Transient,
               enablePrefixCompression: Boolean,
               compressDuplicateValues: Boolean,
               entryId: BaseEntryId.Time,
               plusSize: Int,
               hasPrefixCompression: Boolean,
               normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[T]): EntryWriter.WriteResult =
    if (current.value.forall(_.isEmpty))
      noValue(
        current = current,
        entryId = entryId,
        plusSize = plusSize,
        enablePrefixCompression = enablePrefixCompression,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )
    else
      current.previous match {
        case Some(previous) =>
          compress(
            current = current,
            previous = previous,
            enablePrefixCompression = enablePrefixCompression,
            compressDuplicateValues = compressDuplicateValues,
            entryId = entryId,
            plusSize = plusSize,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )

        case None =>
          uncompressed(
            current = current,
            currentValue = current.value,
            entryId = entryId,
            plusSize = plusSize,
            enablePrefixCompression = enablePrefixCompression,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )
      }

  private def compress[T](current: Transient,
                          previous: Transient,
                          enablePrefixCompression: Boolean,
                          compressDuplicateValues: Boolean,
                          entryId: BaseEntryId.Time,
                          plusSize: Int,
                          hasPrefixCompression: Boolean,
                          normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[T]): EntryWriter.WriteResult =
    if (compressDuplicateValues) //check if values are the same.
      duplicateValue(
        current = current,
        previous = previous,
        entryId = entryId,
        plusSize = plusSize,
        enablePrefixCompression = enablePrefixCompression,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      ) getOrElse {
        partialCompress(
          current = current,
          previous = previous,
          enablePrefixCompression = enablePrefixCompression,
          entryId = entryId,
          plusSize = plusSize,
          hasPrefixCompression = hasPrefixCompression,
          normaliseToSize = normaliseToSize
        )
      }
    else
      partialCompress(
        current = current,
        previous = previous,
        enablePrefixCompression = enablePrefixCompression,
        entryId = entryId,
        plusSize = plusSize,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )

  private def partialCompress[T](current: Transient,
                                 previous: Transient,
                                 enablePrefixCompression: Boolean,
                                 entryId: BaseEntryId.Time,
                                 plusSize: Int,
                                 hasPrefixCompression: Boolean,
                                 normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[T]): EntryWriter.WriteResult =
    if (enablePrefixCompression)
      (Transient.compressibleValue(current), Transient.compressibleValue(previous)) match {
        case (Some(currentValue), Some(previousValue)) =>
          partialCompress(
            current = current,
            entryId = entryId,
            plusSize = plusSize,
            currentValue = currentValue,
            previous = previous,
            previousValue = previousValue,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )

        case (Some(_), None) =>
          uncompressed(
            current = current,
            currentValue = current.value,
            entryId = entryId,
            plusSize = plusSize,
            enablePrefixCompression = enablePrefixCompression,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )

        case (None, _) =>
          noValue(
            current = current,
            entryId = entryId,
            plusSize = plusSize,
            enablePrefixCompression = enablePrefixCompression,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )
      }
    else
      uncompressed(
        current = current,
        currentValue = current.value,
        entryId = entryId,
        plusSize = plusSize,
        enablePrefixCompression = enablePrefixCompression,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )

  private def uncompressed(current: Transient,
                           currentValue: Option[Slice[Byte]],
                           entryId: BaseEntryId.Time,
                           plusSize: Int,
                           enablePrefixCompression: Boolean,
                           hasPrefixCompression: Boolean,
                           normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): EntryWriter.WriteResult = {
    //if previous does not exists write full offsets and then write deadline.
    val currentValueSize = currentValue.foldLeft(0)(_ + _.size)
    val currentValueOffset = current.previous.map(_.nextStartValueOffsetPosition) getOrElse 0
    val currentValueOffsetByteSize = Bytes.sizeOfUnsignedInt(currentValueOffset)
    val currentValueLengthByteSize = Bytes.sizeOfUnsignedInt(currentValueSize)

    val (indexEntryBytes, isPrefixCompressed, accessPosition) =
      DeadlineWriter.write(
        current = current,
        deadlineId = entryId.valueUncompressed.valueOffsetUncompressed.valueLengthUncompressed,
        enablePrefixCompression = enablePrefixCompression,
        plusSize = plusSize + currentValueOffsetByteSize + currentValueLengthByteSize,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )

    indexEntryBytes
      .addUnsignedInt(currentValueOffset)
      .addUnsignedInt(currentValueSize)

    EntryWriter.WriteResult(
      indexBytes = indexEntryBytes,
      valueBytes = currentValue,
      valueStartOffset = currentValueOffset,
      valueEndOffset = currentValueOffset + currentValueSize - 1,
      thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
      isPrefixCompressed = isPrefixCompressed
    )
  }

  private def noValue(current: Transient,
                      entryId: BaseEntryId.Time,
                      plusSize: Int,
                      enablePrefixCompression: Boolean,
                      hasPrefixCompression: Boolean,
                      normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): EntryWriter.WriteResult = {
    //if there is no value then write deadline.
    val (indexEntryBytes, isPrefixCompressed, accessPosition) =
      DeadlineWriter.write(
        current = current,
        deadlineId = entryId.noValue,
        enablePrefixCompression = enablePrefixCompression,
        plusSize = plusSize,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )
    //since there is no value, offsets will continue from previous key-values offset.
    EntryWriter.WriteResult(
      indexBytes = indexEntryBytes,
      valueBytes = None,
      valueStartOffset = current.previous.map(_.currentStartValueOffsetPosition).getOrElse(0),
      valueEndOffset = current.previous.map(_.currentEndValueOffsetPosition).getOrElse(0),
      thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
      isPrefixCompressed = isPrefixCompressed
    )
  }

  private def duplicateValue(current: Transient,
                             previous: Transient,
                             entryId: BaseEntryId.Time,
                             plusSize: Int,
                             enablePrefixCompression: Boolean,
                             hasPrefixCompression: Boolean,
                             normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): Option[EntryWriter.WriteResult] =
    when(Transient.hasSameValue(previous, current)) { //no need to serialised values and then compare. Simply compare the value objects and check for equality.
      val (indexEntry, isPrefixCompressed, accessPosition) =
        if (enablePrefixCompression) {
          //values are the same, no need to write offset & length, jump straight to deadline.
          DeadlineWriter.write(
            current = current,
            deadlineId = entryId.valueFullyCompressed.valueOffsetFullyCompressed.valueLengthFullyCompressed,
            enablePrefixCompression = enablePrefixCompression,
            plusSize = plusSize,
            hasPrefixCompression = true,
            normaliseToSize = normaliseToSize
          )
        } else {
          //use previous values offset but write the offset and length information.
          val currentValueOffsetUnsignedBytes = Slice.writeUnsignedInt(previous.currentStartValueOffsetPosition)
          val currentValueLengthUnsignedBytes = Slice.writeUnsignedInt(previous.currentEndValueOffsetPosition - previous.currentStartValueOffsetPosition + 1)

          val (indexEntryBytes, isPrefixCompressed, accessPosition) =
            DeadlineWriter.write(
              current = current,
              deadlineId = entryId.valueFullyCompressed.valueOffsetUncompressed.valueLengthUncompressed,
              enablePrefixCompression = enablePrefixCompression,
              plusSize = plusSize + currentValueOffsetUnsignedBytes.size + currentValueLengthUnsignedBytes.size,
              hasPrefixCompression = hasPrefixCompression,
              normaliseToSize = normaliseToSize
            )

          indexEntryBytes
            .addAll(currentValueOffsetUnsignedBytes)
            .addAll(currentValueLengthUnsignedBytes)

          (indexEntryBytes, isPrefixCompressed, accessPosition)
        }

      Some(
        EntryWriter.WriteResult(
          indexBytes = indexEntry,
          valueBytes = None,
          valueStartOffset = previous.currentStartValueOffsetPosition,
          valueEndOffset = previous.currentEndValueOffsetPosition,
          thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
          isPrefixCompressed = isPrefixCompressed
        )
      )
    }

  private def partialCompress(current: Transient,
                              entryId: BaseEntryId.Time,
                              plusSize: Int,
                              currentValue: Slice[Byte],
                              previous: Transient,
                              previousValue: Slice[Byte],
                              hasPrefixCompression: Boolean,
                              normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): EntryWriter.WriteResult = {
    //if the values are not the same, write compressed offset, length and then deadline.
    val currentValueOffset = previous.nextStartValueOffsetPosition
    compressValueOffset(
      current = current,
      previous = previous,
      entryId = entryId,
      plusSize = plusSize,
      currentValue = currentValue,
      previousValue = previousValue,
      currentValueOffset = currentValueOffset,
      normaliseToSize = normaliseToSize
    ) getOrElse {
      compressValueLength(
        current = current,
        entryId = entryId,
        plusSize = plusSize,
        currentValue = currentValue,
        previousValue = previousValue,
        currentValueOffset = currentValueOffset,
        hasPrefixCompression = hasPrefixCompression,
        normaliseToSize = normaliseToSize
      )
    }
  }

  private def compressValueOffset(current: Transient,
                                  previous: Transient,
                                  entryId: BaseEntryId.Time,
                                  plusSize: Int,
                                  currentValue: Slice[Byte],
                                  previousValue: Slice[Byte],
                                  currentValueOffset: Int,
                                  normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): Option[EntryWriter.WriteResult] =
    Bytes.compress(Slice.writeInt(previous.currentStartValueOffsetPosition), Slice.writeInt(currentValueOffset), 1) map {
      case (valueOffsetCommonBytes, valueOffsetRemainingBytes) =>
        val valueOffsetId =
          if (valueOffsetCommonBytes == 1)
            entryId.valueUncompressed.valueOffsetOneCompressed
          else if (valueOffsetCommonBytes == 2)
            entryId.valueUncompressed.valueOffsetTwoCompressed
          else if (valueOffsetCommonBytes == 3)
            entryId.valueUncompressed.valueOffsetThreeCompressed
          else
            throw IO.throwable(s"Fatal exception: valueOffsetCommonBytes = $valueOffsetCommonBytes")

        Bytes.compress(Slice.writeInt(previousValue.size), Slice.writeInt(currentValue.size), 1) match {
          case Some((valueLengthCommonBytes, valueLengthRemainingBytes)) =>
            val valueLengthId =
              if (valueLengthCommonBytes == 1)
                valueOffsetId.valueLengthOneCompressed
              else if (valueLengthCommonBytes == 2)
                valueOffsetId.valueLengthTwoCompressed
              else if (valueLengthCommonBytes == 3)
                valueOffsetId.valueLengthThreeCompressed
              else if (valueLengthCommonBytes == 4)
                valueOffsetId.valueLengthFullyCompressed
              else
                throw IO.throwable(s"Fatal exception: valueLengthCommonBytes = $valueLengthCommonBytes")

            val (indexEntryBytes, _, accessPosition) =
              DeadlineWriter.write(
                current = current,
                deadlineId = valueLengthId,
                enablePrefixCompression = true,
                plusSize = plusSize + valueOffsetRemainingBytes.size + valueLengthRemainingBytes.size,
                hasPrefixCompression = true,
                normaliseToSize = normaliseToSize
              )

            indexEntryBytes
              .addAll(valueOffsetRemainingBytes)
              .addAll(valueLengthRemainingBytes)

            EntryWriter.WriteResult(
              indexBytes = indexEntryBytes,
              valueBytes = Some(currentValue),
              valueStartOffset = currentValueOffset,
              valueEndOffset = currentValueOffset + currentValue.size - 1,
              thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
              isPrefixCompressed = true
            )

          case None =>
            //if unable to compress valueLengthBytes then write compressed valueOffset with fully valueLength bytes.
            val currentUnsignedValueLengthBytes = Slice.writeUnsignedInt(currentValue.size)
            val (indexEntryBytes, isPrefixCompressed, accessPosition) =
              DeadlineWriter.write(
                current = current,
                deadlineId = valueOffsetId.valueLengthUncompressed,
                enablePrefixCompression = true,
                plusSize = plusSize + valueOffsetRemainingBytes.size + currentUnsignedValueLengthBytes.size,
                hasPrefixCompression = true,
                normaliseToSize = normaliseToSize
              )

            indexEntryBytes
              .addAll(valueOffsetRemainingBytes)
              .addAll(currentUnsignedValueLengthBytes)

            EntryWriter.WriteResult(
              indexBytes = indexEntryBytes,
              valueBytes = Some(currentValue),
              valueStartOffset = currentValueOffset,
              valueEndOffset = currentValueOffset + currentValue.size - 1,
              thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
              isPrefixCompressed = isPrefixCompressed
            )
        }
    }

  private def compressValueLength(current: Transient,
                                  entryId: BaseEntryId.Time,
                                  plusSize: Int,
                                  currentValue: Slice[Byte],
                                  previousValue: Slice[Byte],
                                  currentValueOffset: Int,
                                  hasPrefixCompression: Boolean,
                                  normaliseToSize: Option[Int])(implicit binder: TransientToKeyValueIdBinder[_]): EntryWriter.WriteResult =
  //if unable to compress valueOffsetBytes, try compressing value length valueLength bytes.
    Bytes.compress(Slice.writeInt(previousValue.size), Slice.writeInt(currentValue.size), 1) match {
      case Some((valueLengthCommonBytes, valueLengthRemainingBytes)) =>
        val valueLengthId =
          if (valueLengthCommonBytes == 1)
            entryId.valueUncompressed.valueOffsetUncompressed.valueLengthOneCompressed
          else if (valueLengthCommonBytes == 2)
            entryId.valueUncompressed.valueOffsetUncompressed.valueLengthTwoCompressed
          else if (valueLengthCommonBytes == 3)
            entryId.valueUncompressed.valueOffsetUncompressed.valueLengthThreeCompressed
          else if (valueLengthCommonBytes == 4)
            entryId.valueUncompressed.valueOffsetUncompressed.valueLengthFullyCompressed
          else
            throw IO.throwable(s"Fatal exception: valueLengthCommonBytes = $valueLengthCommonBytes")

        val currentUnsignedValueOffsetBytes = Slice.writeUnsignedInt(currentValueOffset)
        val (indexEntryBytes, isPrefixCompressed, accessPosition) =
          DeadlineWriter.write(
            current = current,
            deadlineId = valueLengthId,
            enablePrefixCompression = true,
            plusSize = plusSize + currentUnsignedValueOffsetBytes.size + valueLengthRemainingBytes.size,
            hasPrefixCompression = true,
            normaliseToSize = normaliseToSize
          )

        indexEntryBytes
          .addAll(currentUnsignedValueOffsetBytes)
          .addAll(valueLengthRemainingBytes)

        EntryWriter.WriteResult(
          indexBytes = indexEntryBytes,
          valueBytes = Some(currentValue),
          valueStartOffset = currentValueOffset,
          valueEndOffset = currentValueOffset + currentValue.size - 1,
          thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
          isPrefixCompressed = isPrefixCompressed
        )

      case None =>
        //unable to compress valueOffset and valueLength bytes, write them as full bytes.
        val currentUnsignedValueOffsetBytes = Slice.writeUnsignedInt(currentValueOffset)
        val currentUnsignedValueLengthBytes = Slice.writeUnsignedInt(currentValue.size)
        val (indexEntryBytes, isPrefixCompressed, accessPosition) =
          DeadlineWriter.write(
            current = current,
            deadlineId = entryId.valueUncompressed.valueOffsetUncompressed.valueLengthUncompressed,
            enablePrefixCompression = true,
            plusSize = plusSize + currentUnsignedValueOffsetBytes.size + currentUnsignedValueLengthBytes.size,
            hasPrefixCompression = hasPrefixCompression,
            normaliseToSize = normaliseToSize
          )

        indexEntryBytes
          .addAll(currentUnsignedValueOffsetBytes)
          .addAll(currentUnsignedValueLengthBytes)

        EntryWriter.WriteResult(
          indexBytes = indexEntryBytes,
          valueBytes = Some(currentValue),
          valueStartOffset = currentValueOffset,
          valueEndOffset = currentValueOffset + currentValue.size - 1,
          thisKeyValueAccessIndexPosition = accessPosition.getOrElse(0),
          isPrefixCompressed = isPrefixCompressed
        )
    }
}
