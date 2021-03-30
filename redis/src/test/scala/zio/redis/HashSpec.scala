package zio.redis

import zio.Chunk
import zio.random.Random
import zio.test.Assertion._
import zio.test._

trait HashSpec extends BaseSpec {

  val hashSuite: Spec[RedisExecutor with Random with TestConfig, TestFailure[RedisError], TestSuccess] =
    suite("hash")(
      suite("hSet, hGet, hGetAll and hDel")(
        testM("set followed by get") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hGet[String, String, String](hash, field)
          } yield assert(result)(isSome(equalTo(value)))
        },
        testM("set multiple fields for hash") {
          for {
            hash   <- uuid
            field1 <- uuid
            field2 <- uuid
            value  <- uuid
            result <- hSet(hash, field1 -> value, field2 -> value)
          } yield assert(result)(equalTo(2L))
        },
        testM("get all fields for hash") {
          for {
            hash   <- uuid
            field1 <- uuid
            field2 <- uuid
            value  <- uuid
            _      <- hSet(hash, field1 -> value, field2 -> value)
            result <- hGetAll[String, String, String](hash)
          } yield assert(Chunk.fromIterable(result.values))(hasSameElements(Chunk(value, value)))
        },
        testM("delete field for hash") {
          for {
            hash    <- uuid
            field   <- uuid
            value   <- uuid
            _       <- hSet(hash, field -> value)
            deleted <- hDel(hash, field)
            result  <- hGet[String, String, String](hash, field)
          } yield assert(deleted)(equalTo(1L)) && assert(result)(isNone)
        },
        testM("delete multiple fields for hash") {
          for {
            hash    <- uuid
            field1  <- uuid
            field2  <- uuid
            value   <- uuid
            _       <- hSet(hash, field1 -> value, field2 -> value)
            deleted <- hDel(hash, field1, field2)
          } yield assert(deleted)(equalTo(2L))
        }
      ),
      suite("hmSet and hmGet")(
        testM("set followed by get") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hmSet(hash, field -> value)
            result <- hmGet[String, String, String](hash, field)
          } yield assert(result)(hasSameElements(Chunk(Some(value))))
        },
        testM("set multiple fields for hash") {
          for {
            hash    <- uuid
            field1  <- uuid
            field2  <- uuid
            value   <- uuid
            _       <- hmSet(hash, field1 -> value, field2 -> value)
            result1 <- hmGet[String, String, String](hash, field1)
            result2 <- hmGet[String, String, String](hash, field2)
          } yield assert(result1)(hasSameElements(Chunk(Some(value)))) &&
            assert(result2)(hasSameElements(Chunk(Some(value))))
        },
        testM("get multiple fields for hash") {
          for {
            hash   <- uuid
            field1 <- uuid
            field2 <- uuid
            value1 <- uuid
            value2 <- uuid
            _      <- hmSet(hash, field1 -> value1, field2 -> value2)
            result <- hmGet[String, String, String](hash, field1, field2)
          } yield assert(result)(hasSameElements(Chunk(Some(value1), Some(value2))))
        },
        testM("delete field for hash") {
          for {
            hash    <- uuid
            field   <- uuid
            value   <- uuid
            _       <- hmSet(hash, field -> value)
            deleted <- hDel(hash, field)
            result  <- hmGet[String, String, String](hash, field)
          } yield assert(deleted)(equalTo(1L)) && assert(result)(hasSameElements(Chunk(None)))
        },
        testM("delete multiple fields for hash") {
          for {
            hash    <- uuid
            field1  <- uuid
            field2  <- uuid
            field3  <- uuid
            value   <- uuid
            _       <- hmSet(hash, field1 -> value, field2 -> value, field3 -> value)
            deleted <- hDel(hash, field1, field3)
            result  <- hmGet[String, String, String](hash, field1, field2, field3)
          } yield assert(deleted)(equalTo(2L)) &&
            assert(result)(hasSameElements(Chunk(None, Some(value), None)))
        }
      ),
      suite("hExists")(
        testM("field should exist") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hExists(hash, field)
          } yield assert(result)(isTrue)
        },
        testM("field shouldn't exist") {
          for {
            hash   <- uuid
            field  <- uuid
            result <- hExists(hash, field)
          } yield assert(result)(isFalse)
        }
      ),
      suite("hIncrBy and hIncrByFloat")(
        testM("existing field should be incremented by 1") {
          for {
            hash   <- uuid
            field  <- uuid
            _      <- hSet(hash, field -> "1")
            result <- hIncrBy(hash, field, 1L)
          } yield assert(result)(equalTo(2L))
        },
        testM("incrementing value of non-existing hash and filed should create them") {
          for {
            hash   <- uuid
            field  <- uuid
            result <- hIncrBy(hash, field, 1L)
          } yield assert(result)(equalTo(1L))
        },
        testM("existing field should be incremented by 1.5") {
          for {
            hash   <- uuid
            field  <- uuid
            _      <- hSet(hash, field -> "1")
            result <- hIncrByFloat(hash, field, 1.5)
          } yield assert(result)(equalTo(2.5))
        },
        testM("incrementing value of float for non-existing hash and field should create them") {
          for {
            hash   <- uuid
            field  <- uuid
            result <- hIncrByFloat(hash, field, 1.5)
          } yield assert(result)(equalTo(1.5))
        },
        testM("incrementing value of float for non-existing hash and field with negative value") {
          for {
            hash   <- uuid
            field  <- uuid
            result <- hIncrByFloat(hash, field, -1.5)
          } yield assert(result)(equalTo(-1.5))
        }
      ),
      suite("hKeys and hLen")(
        testM("get field names for existing hash") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hKeys[String, String](hash)
          } yield assert(result)(hasSameElements(Chunk(field)))
        },
        testM("get field names for non-existing hash") {
          for {
            hash   <- uuid
            result <- hKeys[String, String](hash)
          } yield assert(result)(isEmpty)
        },
        testM("get field count for existing hash") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hLen(hash)
          } yield assert(result)(equalTo(1L))
        },
        testM("get field count for non-existing hash") {
          for {
            hash   <- uuid
            result <- hLen(hash)
          } yield assert(result)(equalTo(0L))
        }
      ),
      suite("hSetNx")(
        testM("set value for non-existing field") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            result <- hSetNx(hash, field, value)
          } yield assert(result)(isTrue)
        },
        testM("set value for existing field") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hSetNx(hash, field, value)
          } yield assert(result)(isFalse)
        }
      ),
      suite("hStrLen")(
        testM("get value length for existing field") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hStrLen(hash, field)
          } yield assert(result)(equalTo(value.length.toLong))
        },
        testM("get value length for non-existing field") {
          for {
            hash   <- uuid
            field  <- uuid
            result <- hStrLen(hash, field)
          } yield assert(result)(equalTo(0L))
        }
      ),
      suite("hVals")(
        testM("get all values from existing hash") {
          for {
            hash   <- uuid
            field  <- uuid
            value  <- uuid
            _      <- hSet(hash, field -> value)
            result <- hVals[String, String](hash)
          } yield assert(result)(hasSameElements(Chunk(value)))
        },
        testM("get all values from non-existing hash") {
          for {
            hash   <- uuid
            result <- hVals[String, String](hash)
          } yield assert(result)(isEmpty)
        }
      ),
      suite("hScan")(
        testM("hScan entries with match and count options")(checkM(genPatternOption, genCountOption) {
          (pattern, count) =>
            for {
              hash            <- uuid
              field           <- uuid
              value           <- uuid
              _               <- hSet(hash, field -> value)
              scan            <- hScan[String, String, String](hash, 0L, pattern, count)
              (next, elements) = scan
            } yield assert(next)(isGreaterThanEqualTo(0L)) && assert(elements)(isNonEmpty)
        })
      )
    )
}
