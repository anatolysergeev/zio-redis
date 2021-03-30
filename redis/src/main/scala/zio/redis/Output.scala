package zio.redis

import zio.Chunk
import zio.duration._
import zio.schema.Schema
import zio.schema.codec.Codec

sealed trait Output[+A] {
  self =>

  private[redis] final def unsafeDecode(respValue: RespValue)(implicit codec: Codec): A =
    respValue match {
      case RespValue.Error(msg) if msg.startsWith("ERR") =>
        throw RedisError.ProtocolError(msg.drop(3).trim)
      case RespValue.Error(msg) if msg.startsWith("WRONGTYPE") =>
        throw RedisError.WrongType(msg.drop(9).trim)
      case RespValue.Error(msg) if msg.startsWith("BUSYGROUP") =>
        throw RedisError.BusyGroup(msg.drop(9).trim)
      case RespValue.Error(msg) if msg.startsWith("NOGROUP") =>
        throw RedisError.NoGroup(msg.drop(7).trim)
      case RespValue.Error(msg) =>
        throw RedisError.ProtocolError(msg.trim)
      case success =>
        tryDecode(success)
    }

  protected def tryDecode(respValue: RespValue)(implicit codec: Codec): A

  final def map[B](f: A => B): Output[B] =
    new Output[B] {
      protected def tryDecode(respValue: RespValue)(implicit codec: Codec): B = f(self.tryDecode(respValue))
    }

}

object Output {

  import RedisError._

  case object BoolOutput extends Output[Boolean] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Boolean =
      respValue match {
        case RespValue.Integer(0) => false
        case RespValue.Integer(1) => true
        case other                => throw ProtocolError(s"$other isn't a boolean")
      }
  }

  final case class ChunkOutput[+A](output: Output[A]) extends Output[Chunk[A]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[A] =
      respValue match {
        case RespValue.NullArray     => Chunk.empty
        case RespValue.Array(values) => values.map(output.tryDecode)
        case other                   => throw ProtocolError(s"$other isn't an array")
      }
  }

  case object DoubleOutput extends Output[Double] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Double =
      respValue match {
        case RespValue.BulkString(bytes) => decodeDouble(bytes)
        case other                       => throw ProtocolError(s"$other isn't a double.")
      }
  }

  private object DurationOutput extends Output[Long] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Long =
      respValue match {
        case RespValue.Integer(-2L) => throw ProtocolError("Key not found.")
        case RespValue.Integer(-1L) => throw ProtocolError("Key has no expire.")
        case RespValue.Integer(n)   => n
        case other                  => throw ProtocolError(s"$other isn't a duration.")
      }
  }

  final val DurationMillisecondsOutput: Output[Duration] = DurationOutput.map(_.milliseconds)

  final val DurationSecondsOutput: Output[Duration] = DurationOutput.map(_.seconds)

  case object LongOutput extends Output[Long] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Long =
      respValue match {
        case RespValue.Integer(v) => v
        case other                => throw ProtocolError(s"$other isn't an integer")
      }
  }

  final case class OptionalOutput[+A](output: Output[A]) extends Output[Option[A]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Option[A] =
      respValue match {
        case RespValue.NullBulkString | RespValue.NullArray => None
        case other                                          => Some(output.tryDecode(other))
      }
  }

  final case class ScanOutput[+A](output: Output[A]) extends Output[(Long, Chunk[A])] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): (Long, Chunk[A]) =
      respValue match {
        case RespValue.ArrayValues(cursor @ RespValue.BulkString(_), RespValue.Array(items)) =>
          (cursor.asLong, items.map(output.tryDecode))
        case other =>
          throw ProtocolError(s"$other isn't scan output")
      }
  }

  case object KeyElemOutput extends Output[Option[(String, String)]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Option[(String, String)] =
      respValue match {
        case RespValue.NullArray =>
          None
        case RespValue.ArrayValues(a @ RespValue.BulkString(_), b @ RespValue.BulkString(_)) =>
          Some((a.asString, b.asString))
        case other => throw ProtocolError(s"$other isn't blPop output")
      }
  }

  case object StringOutput extends Output[String] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): String =
      respValue match {
        case RespValue.SimpleString(s) => s
        case other                     => throw ProtocolError(s"$other isn't a simple string")
      }
  }

  case object MultiStringOutput extends Output[String] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): String =
      respValue match {
        case s @ RespValue.BulkString(_) => s.asString
        case other                       => throw ProtocolError(s"$other isn't a bulk string")
      }
  }

  case object BulkStringOutput extends Output[Chunk[Byte]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[Byte] =
      respValue match {
        case RespValue.BulkString(value) => value
        case other                       => throw ProtocolError(s"$other isn't a bulk string")
      }
  }

  final case class ArbitraryOutput[A]()(implicit schema: Schema[A]) extends Output[A] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): A =
      respValue match {
        case RespValue.BulkString(s) => codec.decode(schema)(s).fold(e => throw CodecError(e), identity)
        case other                   => throw ProtocolError(s"$other isn't a simple string")
      }
  }

  final case class KeyValueOutput[K, +V](keyOutput: Output[K], valueOutput: Output[V]) extends Output[Map[K, V]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Map[K, V] =
      respValue match {
        case RespValue.Array(elements) if elements.length % 2 == 0 =>
          val output = collection.mutable.Map.empty[K, V]
          val len    = elements.length
          var pos    = 0

          while (pos < len) {
            (elements(pos), elements(pos + 1)) match {
              case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_)) =>
                output += keyOutput.tryDecode(key) -> valueOutput.tryDecode(value)
              case _ =>
            }
            pos += 2
          }

          output.toMap

        case array @ RespValue.Array(_) =>
          throw ProtocolError(s"$array doesn't have an even number of elements")

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  final case class Tuple2Output[+A, +B](_1: Output[A], _2: Output[B]) extends Output[(A, B)] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): (A, B) =
      respValue match {
        case RespValue.ArrayValues(a: RespValue, b: RespValue) => (_1.tryDecode(a), _2.tryDecode(b))
        case other                                             => throw ProtocolError(s"$other isn't scan output")
      }
  }

  case object SingleOrMultiStringOutput extends Output[String] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): String =
      respValue match {
        case RespValue.SimpleString(s)   => s
        case s @ RespValue.BulkString(_) => s.asString
        case other                       => throw ProtocolError(s"$other isn't a bulk string")
      }
  }

  final case class MultiStringChunkOutput[+A](output: Output[A]) extends Output[Chunk[A]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[A] =
      respValue match {
        case RespValue.NullBulkString    => Chunk.empty
        case s @ RespValue.BulkString(_) => Chunk.single(output.tryDecode(s))
        case RespValue.Array(elements)   => elements.map(output.tryDecode)
        case other                       => throw ProtocolError(s"$other isn't a string nor an array")
      }
  }

  case object ChunkOptionalMultiStringOutput extends Output[Chunk[Option[String]]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[Option[String]] =
      respValue match {
        case RespValue.NullArray => Chunk.empty
        case RespValue.Array(elements) =>
          elements.map {
            case RespValue.NullBulkString    => None
            case s @ RespValue.BulkString(_) => Some(s.asString)
            case other                       => throw ProtocolError(s"$other isn't null or a bulk string")
          }
        case other => throw ProtocolError(s"$other isn't an array")
      }
  }

  case object TypeOutput extends Output[RedisType] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): RedisType =
      respValue match {
        case RespValue.SimpleString("string") => RedisType.String
        case RespValue.SimpleString("list")   => RedisType.List
        case RespValue.SimpleString("set")    => RedisType.Set
        case RespValue.SimpleString("zset")   => RedisType.SortedSet
        case RespValue.SimpleString("hash")   => RedisType.Hash
        case RespValue.SimpleString("stream") => RedisType.Stream
        case other                            => throw ProtocolError(s"$other isn't redis type.")
      }
  }

  case object UnitOutput extends Output[Unit] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Unit =
      respValue match {
        case RespValue.SimpleString("OK") => ()
        case other                        => throw ProtocolError(s"$other isn't unit.")
      }
  }

  case object GeoOutput extends Output[Chunk[Option[LongLat]]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[Option[LongLat]] =
      respValue match {
        case RespValue.NullArray =>
          Chunk.empty
        case RespValue.Array(elements) =>
          elements.map {
            case RespValue.NullArray => None
            case RespValue.ArrayValues(RespValue.BulkString(long), RespValue.BulkString(lat)) =>
              Some(LongLat(decodeDouble(long), decodeDouble(lat)))
            case other =>
              throw ProtocolError(s"$other was not a longitude,latitude pair")
          }
        case other =>
          throw ProtocolError(s"$other isn't geo output")
      }
  }

  case object GeoRadiusOutput extends Output[Chunk[GeoView]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[GeoView] =
      respValue match {
        case RespValue.Array(elements) =>
          elements.map {
            case s @ RespValue.BulkString(_) =>
              GeoView(s.asString, None, None, None)
            case RespValue.ArrayValues(name @ RespValue.BulkString(_), infos @ _*) =>
              val distance = infos.collectFirst { case RespValue.BulkString(bytes) => decodeDouble(bytes) }

              val hash = infos.collectFirst { case RespValue.Integer(i) => i }

              val position = infos.collectFirst {
                case RespValue.ArrayValues(RespValue.BulkString(long), RespValue.BulkString(lat)) =>
                  LongLat(decodeDouble(long), decodeDouble(lat))
              }

              GeoView(name.asString, distance, hash, position)

            case other => throw ProtocolError(s"$other is not a geo radious output")
          }

        case other => throw ProtocolError(s"$other is not an array")
      }
  }

  case object StreamOutput extends Output[Map[String, Map[String, String]]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Map[String, Map[String, String]] =
      respValue match {
        case RespValue.NullArray       => Map.empty[String, Map[String, String]]
        case RespValue.Array(entities) => extractNestedMap(entities)
        case other                     => throw ProtocolError(s"$other isn't an array")
      }
  }

  private def extractNestedMap(entities: Chunk[RespValue])(implicit codec: Codec): Map[String, Map[String, String]] = {
    val output = collection.mutable.Map.empty[String, Map[String, String]]
    entities.foreach {
      case RespValue.Array(Seq(id @ RespValue.BulkString(_), value)) =>
        output += (id.asString -> KeyValueOutput(MultiStringOutput, MultiStringOutput).unsafeDecode(value))
      case other =>
        throw ProtocolError(s"$other isn't a valid array")
    }
    output.toMap
  }

  case object StreamGroupsInfoOutput extends Output[Chunk[StreamGroupsInfo]] {
    override protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[StreamGroupsInfo] =
      respValue match {
        case RespValue.NullArray => Chunk.empty
        case RespValue.Array(messages) =>
          messages.collect {
            // Note that you should not rely on the fields exact position. see https://redis.io/commands/xinfo
            case RespValue.Array(elements) if elements.length % 2 == 0 =>
              var streamGroupsInfo: StreamGroupsInfo = StreamGroupsInfo.empty
              val len                                = elements.length
              var pos                                = 0
              while (pos < len) {
                (elements(pos), elements(pos + 1)) match {
                  case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_)) =>
                    if (key.asString == XInfoFields.Name)
                      streamGroupsInfo = streamGroupsInfo.copy(name = value.asString)
                    else if (key.asString == XInfoFields.LastDeliveredId)
                      streamGroupsInfo = streamGroupsInfo.copy(lastDeliveredId = value.asString)
                  case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_)) =>
                    if (key.asString == XInfoFields.Pending)
                      streamGroupsInfo = streamGroupsInfo.copy(pending = value.value)
                    else if (key.asString == XInfoFields.Consumers)
                      streamGroupsInfo = streamGroupsInfo.copy(consumers = value.value)
                  case _ =>
                }
                pos += 2
              }
              streamGroupsInfo
            case array @ RespValue.Array(_) =>
              throw ProtocolError(s"$array doesn't have an even number of elements")
            case other =>
              throw ProtocolError(s"$other isn't an array")
          }

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  case object StreamConsumersInfoOutput extends Output[Chunk[StreamConsumersInfo]] {
    override protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[StreamConsumersInfo] =
      respValue match {
        case RespValue.NullArray => Chunk.empty
        case RespValue.Array(messages) =>
          messages.collect {
            // Note that you should not rely on the fields exact position. see https://redis.io/commands/xinfo
            case RespValue.Array(elements) if elements.length % 2 == 0 =>
              var streamConsumersInfo: StreamConsumersInfo = StreamConsumersInfo.empty
              val len                                      = elements.length
              var pos                                      = 0
              while (pos < len) {
                (elements(pos), elements(pos + 1)) match {
                  case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_))
                      if key.asString == XInfoFields.Name =>
                    streamConsumersInfo = streamConsumersInfo.copy(name = value.asString)
                  case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_)) =>
                    if (key.asString == XInfoFields.Pending)
                      streamConsumersInfo = streamConsumersInfo.copy(pending = value.value)
                    else if (key.asString == XInfoFields.Idle)
                      streamConsumersInfo = streamConsumersInfo.copy(idle = value.value.millis)
                  case _ =>
                }
                pos += 2
              }
              streamConsumersInfo
            case array @ RespValue.Array(_) =>
              throw ProtocolError(s"$array doesn't have an even number of elements")
            case other =>
              throw ProtocolError(s"$other isn't an array")
          }

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  case object StreamInfoFullOutput extends Output[StreamInfoWithFull.FullStreamInfo] {
    override protected def tryDecode(respValue: RespValue)(implicit codec: Codec): StreamInfoWithFull.FullStreamInfo = {
      var streamInfoFull: StreamInfoWithFull.FullStreamInfo = StreamInfoWithFull.FullStreamInfo.empty
      respValue match {
        // Note that you should not rely on the fields exact position. see https://redis.io/commands/xinfo
        case RespValue.Array(elements) if elements.length % 2 == 0 =>
          var pos = 0
          while (pos < elements.length) {
            (elements(pos), elements(pos + 1)) match {
              // Get the basic information of the outermost stream.
              case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_)) =>
                key.asString match {
                  case XInfoFields.Length         => streamInfoFull = streamInfoFull.copy(length = value.value)
                  case XInfoFields.RadixTreeNodes => streamInfoFull = streamInfoFull.copy(radixTreeNodes = value.value)
                  case XInfoFields.RadixTreeKeys  => streamInfoFull = streamInfoFull.copy(radixTreeKeys = value.value)
                  case _                          =>
                }
              case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_))
                  if key.asString == XInfoFields.LastGeneratedId =>
                streamInfoFull = streamInfoFull.copy(lastGeneratedId = value.asString)
              case (key @ RespValue.BulkString(_), RespValue.Array(values)) if key.asString == XInfoFields.Entries =>
                val streamEntryList = values.map(extractStreamEntry)
                streamInfoFull = streamInfoFull.copy(entries = streamEntryList)
              case (key @ RespValue.BulkString(_), RespValue.Array(values)) if key.asString == XInfoFields.Groups =>
                // Get the group list of the stream.
                streamInfoFull = streamInfoFull.copy(groups = values.map(extractXInfoFullGroup))
              case _ =>
            }
            pos += 2
          }
          streamInfoFull
        case array @ RespValue.Array(_) =>
          throw ProtocolError(s"$array doesn't have an even number of elements")
        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
    }
  }

  private def extractXInfoFullGroup(group: RespValue): StreamInfoWithFull.ConsumerGroups = {
    var readyGroup = StreamInfoWithFull.ConsumerGroups.empty
    group match {
      case RespValue.Array(groupElements) if groupElements.length % 2 == 0 =>
        var groupElementPos = 0
        while (groupElementPos < groupElements.length) {
          (groupElements(groupElementPos), groupElements(groupElementPos + 1)) match {
            // Get the basic information of the current group.
            case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_)) =>
              key.asString match {
                case XInfoFields.LastDeliveredId => readyGroup = readyGroup.copy(lastDeliveredId = value.asString)
                case XInfoFields.Name            => readyGroup = readyGroup.copy(name = value.asString)
                case _                           =>
              }
            case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_))
                if XInfoFields.PelCount == key.asString =>
              readyGroup = readyGroup.copy(pelCount = value.value)
            case (key @ RespValue.BulkString(_), RespValue.Array(values)) =>
              // Get the consumer list of the current group.
              key.asString match {
                case XInfoFields.Consumers =>
                  readyGroup = readyGroup.copy(consumers = values.map(extractXInfoFullConsumer))
                case XInfoFields.Pending =>
                  // Get the pel list of the current group.
                  val groupPelList = values.map {
                    case RespValue.Array(
                          Seq(
                            entryId @ RespValue.BulkString(_),
                            consumerName @ RespValue.BulkString(_),
                            deliveryTime @ RespValue.Integer(_),
                            deliveryCount @ RespValue.Integer(_)
                          )
                        ) =>
                      StreamInfoWithFull.GroupPel(
                        entryId.asString,
                        consumerName.asString,
                        deliveryTime.value.millis,
                        deliveryCount.value
                      )
                    case other => throw ProtocolError(s"$other isn't a valid array")
                  }
                  readyGroup = readyGroup.copy(pending = groupPelList)
                case _ =>
              }
            case _ =>
          }
          groupElementPos += 2
        }
        readyGroup
      case array @ RespValue.Array(_) =>
        throw ProtocolError(s"$array doesn't have an even number of elements")
      case other =>
        throw ProtocolError(s"$other isn't an array")
    }
  }

  private def extractXInfoFullConsumer(consumer: RespValue): StreamInfoWithFull.Consumers = {
    var readyConsumer = StreamInfoWithFull.Consumers.empty
    consumer match {
      case RespValue.Array(consumerElements) if consumerElements.length % 2 == 0 =>
        var consumerElementPos = 0
        while (consumerElementPos < consumerElements.length) {
          (consumerElements(consumerElementPos), consumerElements(consumerElementPos + 1)) match {
            // Get the basic information of the current consumer.
            case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_)) if key.asString == XInfoFields.Name =>
              readyConsumer = readyConsumer.copy(name = value.asString)
            case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_)) =>
              key.asString match {
                case XInfoFields.PelCount => readyConsumer = readyConsumer.copy(pelCount = value.value)
                case XInfoFields.SeenTime => readyConsumer = readyConsumer.copy(seenTime = value.value.millis)
                case _                    =>
              }
            // Get the pel list of the current consumer.
            case (key @ RespValue.BulkString(_), RespValue.Array(values)) if key.asString == XInfoFields.Pending =>
              val consumerPelList = values.map {
                case RespValue.Array(
                      Seq(
                        entryId @ RespValue.BulkString(_),
                        deliveryTime @ RespValue.Integer(_),
                        deliveryCount @ RespValue.Integer(_)
                      )
                    ) =>
                  StreamInfoWithFull.ConsumerPel(entryId.asString, deliveryTime.value.millis, deliveryCount.value)
                case other => throw ProtocolError(s"$other isn't a valid array")
              }
              readyConsumer = readyConsumer.copy(pending = consumerPelList)
            case _ =>
          }
          consumerElementPos += 2
        }
        readyConsumer
      case array @ RespValue.Array(_) =>
        throw ProtocolError(s"$array doesn't have an even number of elements")
      case other =>
        throw ProtocolError(s"$other isn't an array")
    }
  }

  case object StreamInfoOutput extends Output[StreamInfo] {
    override protected def tryDecode(respValue: RespValue)(implicit codec: Codec): StreamInfo = {
      var streamInfo: StreamInfo = StreamInfo.empty
      respValue match {
        // Note that you should not rely on the fields exact position. see https://redis.io/commands/xinfo
        case RespValue.Array(elements) if elements.length % 2 == 0 =>
          val len = elements.length
          var pos = 0
          while (pos < len) {
            (elements(pos), elements(pos + 1)) match {
              case (key @ RespValue.BulkString(_), value @ RespValue.Integer(_)) =>
                if (key.asString == XInfoFields.Length)
                  streamInfo = streamInfo.copy(length = value.value)
                else if (key.asString == XInfoFields.RadixTreeNodes)
                  streamInfo = streamInfo.copy(radixTreeNodes = value.value)
                else if (key.asString == XInfoFields.RadixTreeKeys)
                  streamInfo = streamInfo.copy(radixTreeKeys = value.value)
                else if (key.asString == XInfoFields.Groups)
                  streamInfo = streamInfo.copy(groups = value.value)
              case (key @ RespValue.BulkString(_), value @ RespValue.BulkString(_))
                  if key.asString == XInfoFields.LastGeneratedId =>
                streamInfo = streamInfo.copy(lastGeneratedId = value.asString)
              case (key @ RespValue.BulkString(_), value @ RespValue.Array(_)) =>
                if (key.asString == XInfoFields.FirstEntry)
                  streamInfo = streamInfo.copy(firstEntry = Some(extractStreamEntry(value)))
                else if (key.asString == XInfoFields.LastEntry)
                  streamInfo = streamInfo.copy(lastEntry = Some(extractStreamEntry(value)))
              case _ =>
            }
            pos += 2
          }
          streamInfo
        case array @ RespValue.Array(_) =>
          throw ProtocolError(s"$array doesn't have an even number of elements")

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
    }
  }

  private def extractStreamEntry(es: RespValue): StreamEntry = {
    val entry           = collection.mutable.Map.empty[String, String]
    var entryId: String = ""
    es match {
      case RespValue.Array(entities) =>
        entities.foreach {
          case id @ RespValue.BulkString(_) => entryId = id.asString
          case RespValue.ArrayValues(id @ RespValue.BulkString(_), value @ RespValue.BulkString(_)) =>
            entry += (id.asString -> value.asString)
          case other =>
            throw ProtocolError(s"$other isn't a valid array")
        }
      case other =>
        throw ProtocolError(s"$other isn't a valid array")
    }
    StreamEntry(id = entryId, entry.toMap)
  }

  case object XPendingOutput extends Output[PendingInfo] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): PendingInfo =
      respValue match {
        case RespValue.Array(Seq(RespValue.Integer(total), f, l, ps)) =>
          val first = OptionalOutput(MultiStringOutput).unsafeDecode(f)
          val last  = OptionalOutput(MultiStringOutput).unsafeDecode(l)

          val pairs = ps match {
            case RespValue.NullArray    => Chunk.empty
            case RespValue.Array(value) => value
            case other                  => throw ProtocolError(s"$other isn't an array")
          }

          val consumers = collection.mutable.Map.empty[String, Long]

          pairs.foreach {
            case RespValue.Array(Seq(consumer @ RespValue.BulkString(_), total @ RespValue.BulkString(_))) =>
              consumers += (consumer.asString -> total.asLong)
            case _ =>
              throw ProtocolError(s"Consumers doesn't have 2 elements")
          }

          PendingInfo(total, first, last, consumers.toMap)

        case array @ RespValue.Array(_) =>
          throw ProtocolError(s"$array doesn't have valid format")

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  case object PendingMessagesOutput extends Output[Chunk[PendingMessage]] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Chunk[PendingMessage] =
      respValue match {
        case RespValue.Array(messages) =>
          messages.collect {
            case RespValue.Array(
                  Seq(
                    id @ RespValue.BulkString(_),
                    owner @ RespValue.BulkString(_),
                    RespValue.Integer(lastDelivered),
                    RespValue.Integer(counter)
                  )
                ) =>
              PendingMessage(id.asString, owner.asString, lastDelivered.millis, counter)
            case other =>
              throw ProtocolError(s"$other isn't an array with four elements")
          }

        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  case object XReadOutput extends Output[Map[String, Map[String, Map[String, String]]]] {
    protected def tryDecode(
      respValue: RespValue
    )(implicit codec: Codec): Map[String, Map[String, Map[String, String]]] =
      respValue match {
        case RespValue.NullArray =>
          Map.empty[String, Map[String, Map[String, String]]]
        case RespValue.Array(streams) =>
          val output = collection.mutable.Map.empty[String, Map[String, Map[String, String]]]
          streams.foreach {
            case RespValue.Array(Seq(id @ RespValue.BulkString(_), value)) =>
              output += (id.asString -> StreamOutput.unsafeDecode(value))
            case other =>
              throw ProtocolError(s"$other isn't an array with two elements")
          }

          output.toMap
        case other =>
          throw ProtocolError(s"$other isn't an array")
      }
  }

  case object SetOutput extends Output[Boolean] {
    protected def tryDecode(respValue: RespValue)(implicit codec: Codec): Boolean =
      respValue match {
        case RespValue.NullBulkString  => false
        case RespValue.SimpleString(_) => true
        case other                     => throw ProtocolError(s"$other isn't a valid set response")
      }
  }

  private def decodeDouble(bytes: Chunk[Byte]): Double = {
    val text = RespValue.decode(bytes)
    try text.toDouble
    catch {
      case _: NumberFormatException => throw ProtocolError(s"'$text' isn't a double.")
    }
  }
}
