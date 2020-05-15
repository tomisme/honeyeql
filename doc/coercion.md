### Type Mappings

While retrieving the data from the database, HoneyEQL coerce the return value to the corresponding JVM type as mentioned in the below table.

| Type             | Postgres                                                                                   | MySQL                                                                                                 |
| ---------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| java.lang.Long   | `integer`, `int`, `int2` `int4`, `smallint`, `smallserial`, `serial`, `serial2`, `serial4`, `bigint`,`int8`,`bigserial`,`serial8` | `SMALLINT`, `MEDIUMINT`, `INT`, `TINYINT UNSIGNED`, `SMALLINT UNSIGNED`, `MEDIUMINT UNSIGNED`, `YEAR`, `INT UNSIGNED`, `BIGINT` |
| java.math.BigDecimal |  `real`, `float4`, `float8`, `double precision`,`numeric`,`decimal`                                                                                        |           `REAL`, `FLOAT`, `DOUBLE`, `DECIMAL`, `NUMERIC`                                                                                            |
|   java.lang.String               |     `bit`, `bit varying`, `char`, `character varying`, `varchar`, `citext`, `bpchar`, `macaddr8`, `text`, `money`                                                                                       |       `CHAR`, `VARCHAR`, `TINYTEXT`, `TEXT`, `MEDIUMTEXT`, `LONGTEXT`, `ENUM`, `SET`, `BINARY`, `VARBINARY`, `TINYBLOB,` `BLOB`, `LONGBLOB`, `BIT`                                                                                                |
| java.lang.Boolean | `boolean` | `TINYINT(1)`, `BIT(1)`|
|          java.util.UUID        |          `uuid`                                                                                  |                                                 --                                                      |
| java.time.LocalDate | `date`| `DATE`|
| java.time.LocalTime | `time`, `time without time zone`| `TIME`|
| java.time.OffsetTime | `timetz`, `time with time zone` | --|
| java.time.LocalDateTime | `timestamp`, `timestamp without time zone` | `DATETIME`, `TIMESTAMP` |
| java.time.OffsetDateTime| `timestamptz`, `timestamp with time zone` | -- | 
