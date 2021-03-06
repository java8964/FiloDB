package filodb.spark

import akka.actor.ActorRef
import com.typesafe.config.Config
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.ByteBuffer
import kamon.Kamon
import net.ceedubs.ficus.Ficus._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.sql.catalyst.expressions.{BaseGenericInternalRow, GenericInternalRow}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.unsafe.types.UTF8String
import org.joda.time.DateTime
import org.velvia.filo.{FiloRowReader, FiloVector, RowReader, VectorReader}
import scala.concurrent.duration._
import scala.language.postfixOps

import filodb.coordinator.client.Client.parse
import filodb.core._
import filodb.core.binaryrecord.BinaryRecord
import filodb.core.query.KeyFilter
import filodb.core.metadata.{Column, DataColumn, Dataset, RichProjection}
import filodb.core.store._

object FiloRelation extends StrictLogging {
  import TypeConverters._

  implicit val context = scala.concurrent.ExecutionContext.Implicits.global

  // *** Statistics **
  // For now they are global across all tables.
  // TODO(velvia): Make them per-table?
  val totalQueries             = Kamon.metrics.counter("spark-queries-total")
  val singlePartQueries        = Kamon.metrics.counter("spark-queries-single-partition")
  val multiPartQueries         = Kamon.metrics.counter("spark-queries-multi-partition")
  val fullFilteredQueries      = Kamon.metrics.counter("spark-queries-full-filtered")
  val fullTableQueries         = Kamon.metrics.counter("spark-queries-full-table")

  def getDatasetObj(dataset: DatasetRef): Dataset =
    parse(FiloDriver.metaStore.getDataset(dataset)) { ds => ds }

  def getSchema(dataset: DatasetRef, version: Int): Column.Schema =
    parse(FiloDriver.metaStore.getSchema(dataset, version)) { schema => schema }

  // Parses the Spark filters, mapping column names to the filters
  def parseFilters(filters: Seq[Filter]): Map[String, Seq[Filter]] = {
    logger.info(s"Incoming filters = $filters")
    filters.collect {
      case f @ EqualTo(col, _) => col -> f
      case f @ In(col, _)      => col -> f
      case f @ GreaterThan(col, _) => col -> f
      case f @ GreaterThanOrEqual(col, _) => col -> f
      case f @ LessThan(col, _) => col -> f
      case f @ LessThanOrEqual(col, _) => col -> f
    }.groupBy(_._1).mapValues( colFilterPairs => colFilterPairs.map(_._2))
  }

  // Parses the Spark filters, matching them to partition key columns, and returning
  // tuples of (position, keytype, applicable filters).
  // NOTE: for now support just one filter per column
  def parsePartitionFilters(projection: RichProjection,
                            groupedFilters: Map[String, Seq[Filter]]): Seq[(Int, KeyType, Seq[Filter])] = {
    val columnIdxTypeMap = KeyFilter.mapPartitionColumns(projection, groupedFilters.keys.toSeq)

    columnIdxTypeMap.map { case (colName, (pos, keyType)) =>
      logger.info(s"Pushing down partition column $colName, filters ${groupedFilters(colName)}")
      (pos, keyType, groupedFilters(colName))
    }.toSeq
  }

  // Partition Query?
  def partitionQuery(config: Config,
                     projection: RichProjection,
                     filterStuff: Seq[(Int, KeyType, Seq[Filter])]): Seq[Any] = {
    val inqueryPartitionsLimit = config.getInt("columnstore.inquery-partitions-limit")
    // Are all the partition keys given in filters?
    // Are the filters EqualTo, In?
    val predicateValues = filterStuff.collect {
      case (pos, keyType, Seq(EqualTo(_, equalValue))) =>
        Set(KeyFilter.parseSingleValue(keyType)(equalValue))
      case (pos, keyType, Seq(In(_, inValues))) =>
        (KeyFilter.parseValues(keyType)(inValues.toSet))
    }
    logger.info(s"Push down partition predicates: ${predicateValues.toString()}")
    // 1. Verify if all partition keys are part of the filters or not.
    // 2. If all present then get all possible coombinations of partition keys by calling combine method.
    // 3. If number of partition key combinations are more than inqueryPartitionsLimit then
    // run full table scan otherwise run multipartition scan.
    if (predicateValues.length == projection.partitionColumns.length) {
      if (predicateValues.length == 1) {
        predicateValues.flatten
      } else {
        val predList = combine(predicateValues)
        if (predList.size <= inqueryPartitionsLimit) predList else Nil
      }
    } else {
      Nil
    }
  }

  /**
    * Method to get possible combinations of key values provided in partition key filters.
    * For example filters provided in the query are actor2Code IN ('JPN', 'KHM') and year = 1979
    * then this method returns all combinations like ('JPN',1979) , ('KHM',1979) which represents
    * full partition key value.
    */
  def combine[A](xs: Traversable[Traversable[A]]): Seq[Seq[A]] =
    xs.filter(_.nonEmpty).foldLeft(Seq(Seq.empty[A])) {
      (x, y) => for {a <- x; b <- y} yield a :+ b
    }

  // PFs for evaluating range scans
  val equalsRangePF: PartialFunction[(KeyType, Seq[Filter]), Option[(Any, Any)]] = {
    case (_, Seq(EqualTo(_, filterValue))) => Some((filterValue, filterValue))
  }

  val betweenRangePF: PartialFunction[(KeyType, Seq[Filter]), Option[(Any, Any)]] = {
    case (_, Seq(GreaterThan(_, lVal),        LessThan(_, rVal))) => Some((lVal, rVal))
    case (_, Seq(GreaterThanOrEqual(_, lVal), LessThan(_, rVal))) => Some((lVal, rVal))
    case (_, Seq(GreaterThan(_, lVal),        LessThanOrEqual(_, rVal))) => Some((lVal, rVal))
    case (_, Seq(GreaterThanOrEqual(_, lVal), LessThanOrEqual(_, rVal))) => Some((lVal, rVal))
  }

  val defaultRangePF: PartialFunction[(KeyType, Seq[Filter]), Option[(Any, Any)]] = {
    case other: Any                        => None
  }

  val nonLastPartPF = (equalsRangePF orElse defaultRangePF)
  val lastPartPF = (equalsRangePF orElse betweenRangePF orElse defaultRangePF)

  /**
   * Evaluate if we can do range scans within a partition's chunks given the filters from Spark.
   */
  def chunkRangeScan(projection: RichProjection,
                     groupedFilters: Map[String, Seq[Filter]]): ChunkScanMethod = {
    val columnIdxTypeMap = KeyFilter.mapRowKeyColumns(projection, groupedFilters.keys.toSeq)

    val posTypeFilters = columnIdxTypeMap.map { case (colName, (pos, keyType)) =>
      logger.debug(s"For row key column $colName, filters are ${groupedFilters(colName)}")
      pos -> (keyType -> groupedFilters(colName))
    }

    val sortedPositions = posTypeFilters.keys.toBuffer.sorted

    if (columnIdxTypeMap.isEmpty) { AllChunkScan }
    // Step 1: Only valid filters are on columns from 0 to n where n < # row keys
    else if (sortedPositions.last == sortedPositions.length - 1) {
      // Step 2: The valid range filters are:
      //   (=)
      //   (><)
      //   (=, ><)
      //   (=, =)
      //   (=, =, ><)  etc.
      //   IE the last component must be either = or >< and all others must be =
      val firstRanges = (sortedPositions dropRight 1).map { pos => nonLastPartPF(posTypeFilters(pos)) }
      val ranges = firstRanges :+ lastPartPF(posTypeFilters(sortedPositions.last))
      val invalidPositions = ranges.zipWithIndex.collect { case (None, idx) => idx }
      if (invalidPositions.nonEmpty) {
        invalidPositions.foreach { pos =>
          logger.info(s"Filters ${posTypeFilters(pos)._2} cannot be pushed down for rowkey range scans")
        }
        AllChunkScan
      } else {
        val firstKey = BinaryRecord(projection, ranges.map(_.get._1))
        val lastKey  = BinaryRecord(projection, ranges.map(_.get._2))
        logger.info(s"Pushdown of rowkey scan ($firstKey, $lastKey)")
        RowKeyChunkScan(firstKey, lastKey)
      }
    } else {
      logger.info(s"Filters $groupedFilters skipped some row key columns, must be from row key columns 0..n")
      logger.info(s"...but are in positions $sortedPositions")
      AllChunkScan
    }
  }

  // Returns filter function, and true if function is doing any filtering
  def filtersToFunc(projection: RichProjection,
                    filterStuff: Seq[(Int, KeyType, Seq[Filter])]): (Any => Boolean, Boolean) = {
    import KeyFilter._

    def toFunc(keyType: KeyType, f: Filter): Any => Boolean = f match {
      case EqualTo(_, value) => equalsFunc(keyType)(parseSingleValue(keyType)(value))
      case In(_, values)     => inFunc(keyType)(parseValues(keyType)(values.toSet).toSet)
      case other: Filter     => throw new IllegalArgumentException(s"Sorry, filter $other not supported")
    }

    // Compute one func per column/position
    logger.debug(s"Filters by position: $filterStuff")
    val funcs = filterStuff.map { case (pos, keyType, filters) =>
      filters.tail.foldLeft(toFunc(keyType, filters.head)) { case (curFunc, newFilter) =>
        andFunc(curFunc, toFunc(keyType, newFilter))
      }
    }

    if (funcs.isEmpty) {
      logger.info(s"Scanning all partitions with no partition key filtering")
      ((a: Any) => true, false)
    } else {
      (makePartitionFilterFunc(projection, filterStuff.map(_._1), funcs), true)
    }
  }

  def splitsQuery(sqlContext: SQLContext,
                  dataset: DatasetRef,
                  splitsPerNode: Int,
                  config: Config,
                  readOnlyProjStr: String,
                  chunkMethod: ChunkScanMethod,
                  version: Int)(f: ScanSplit => PartitionScanMethod): RDD[Row] = {
    val splits = FiloDriver.columnStore.getScanSplits(dataset, splitsPerNode)
    logger.info(s"${splits.length} splits: [${splits.take(3).mkString(", ")}...]")

    val splitsWithLocations = splits.map { s => (s, s.hostnames.toSeq) }
    // NOTE: It's critical that the closure inside mapPartitions only references
    // vars from buildScan() method, and not the FiloRelation class.  Otherwise
    // the entire FiloRelation class would get serialized.
    // Also, each partition should only need one param.
    sqlContext.sparkContext.makeRDD(splitsWithLocations)
      .mapPartitions { splitIter =>
        perPartitionRowScanner(config, readOnlyProjStr, version, f(splitIter.next), chunkMethod)
      }
  }

  // It's good to put complex functions inside an object, to be sure that everything
  // inside the function does not depend on an explicit outer class and can be serializable
  def perPartitionRowScanner(config: Config,
                             readOnlyProjectionString: String,
                             version: Int,
                             partMethod: PartitionScanMethod,
                             chunkMethod: ChunkScanMethod): Iterator[Row] = {
    // NOTE: all the code inside here runs distributed on each node.  So, create my own datastore, etc.
    FiloExecutor.init(config)
    FiloExecutor.columnStore    // force startup
    val readOnlyProjection = RichProjection.readOnlyFromString(readOnlyProjectionString)

    FiloExecutor.columnStore.scanRows(
      readOnlyProjection, readOnlyProjection.columns, version, partMethod, chunkMethod,
      (bytes, clazzes, len) => new SparkRowReader(bytes, clazzes, len)).asInstanceOf[Iterator[Row]]
  }
}

/**
 * Schema and row scanner, with pruned column optimization for fast reading from FiloDB
 *
 * NOTE: Each Spark partition is given 1 to N Filo partitions, and the code sequentially
 * reads data from each partition.  Within each partition read, actors/futures are used to
 * parallelize reads from different columns.
 *
 * @constructor
 * @param sparkContext the spark context to pull config from
 * @param dataset the DatasetRef with name and database/keyspace of the dataset to read from
 * @param the version of the dataset data to read
 */
case class FiloRelation(dataset: DatasetRef,
                        version: Int = 0,
                        splitsPerNode: Int = 1)
                       (@transient val sqlContext: SQLContext)
    extends BaseRelation with InsertableRelation with PrunedScan with PrunedFilteredScan with StrictLogging {
  import TypeConverters._
  import FiloRelation._

  val filoConfig = FiloDriver.initAndGetConfig(sqlContext.sparkContext)

  val datasetObj = getDatasetObj(dataset)
  val filoSchema = getSchema(dataset, version)

  val schema = StructType(columnsToSqlFields(filoSchema.values.toSeq))
  logger.info(s"Read schema for dataset $dataset = $schema")

  // Return false when returning RDD[InternalRow]
  override def needConversion: Boolean = false

  override def insert(data: DataFrame, overwrite: Boolean): Unit =
    sqlContext.insertIntoFilo(data, dataset.dataset, version, overwrite, dataset.database)

  def buildScan(): RDD[Row] = buildScan(filoSchema.keys.toArray)

  def buildScan(requiredColumns: Array[String]): RDD[Row] =
    buildScan(requiredColumns, Array.empty)

  def scanByPartitions(projection: RichProjection,
                       groupedFilters: Map[String, Seq[Filter]],
                       partitionFilters: Seq[(Int, KeyType, Seq[Filter])],
                       readOnlyProjStr: String): RDD[Row] = {
    val _config = this.filoConfig
    val _version = this.version
    totalQueries.increment
    val chunkMethod = chunkRangeScan(projection, groupedFilters)
    partitionQuery(_config, projection, partitionFilters) match {
      // single partition query
      case Seq(partitionKey) =>
        singlePartQueries.increment
        sqlContext.sparkContext.parallelize(Seq(partitionKey), 1).mapPartitions { partKeyIter =>
          perPartitionRowScanner(_config, readOnlyProjStr, _version,
                                 SinglePartitionScan(partKeyIter.next), chunkMethod)
        }

      // filtered partition full table scan, with or without range scanning
      case Nil =>
        val (filterFunc, isFiltered) = filtersToFunc(projection, partitionFilters)
        if (isFiltered) fullFilteredQueries.increment else fullTableQueries.increment
        splitsQuery(sqlContext, dataset, splitsPerNode, _config, readOnlyProjStr, chunkMethod, version) { s =>
          FilteredPartitionScan(s, filterFunc)
        }

      // multi partition query, no range scan
      case partitionKeys: Seq[Any] =>
        multiPartQueries.increment
        sqlContext.sparkContext.parallelize(partitionKeys, 1).mapPartitions { partKeyIter =>
          perPartitionRowScanner(_config, readOnlyProjStr, _version,
                                 MultiPartitionScan(partKeyIter.toSeq), chunkMethod)
        }
    }
  }

  // create fake column for select count(*) queries so we can at least count # rows
  private val countStarCol = DataColumn(0, "*", dataset.dataset, 0, Column.ColumnType.IntColumn)

  def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    // Define vars to distribute inside the method
    val projection = RichProjection(this.datasetObj, filoSchema.values.toSeq :+ countStarCol)
    val filoColumns =  // for select count(*), read a fake column just to get row counts
      if (requiredColumns.isEmpty) Seq(countStarCol) else requiredColumns.toSeq.map(this.filoSchema)
    logger.info(s"Scanning columns $filoColumns")
    val readOnlyProjStr = projection.toReadOnlyProjString(filoColumns.map(_.name))
    logger.debug(s"readOnlyProjStr = $readOnlyProjStr")

    val groupedFilters = parseFilters(filters.toList)
    val partitionFilters = parsePartitionFilters(projection, groupedFilters)

    scanByPartitions(projection, groupedFilters, partitionFilters, readOnlyProjStr)
  }
}

object SparkRowReader {
  import VectorReader._
  import FiloVector._

  val TimestampClass = classOf[java.sql.Timestamp]

  // Customize the Filo vector maker to return Long vectors for Timestamp columns.
  // This is because Spark 1.5's InternalRow expects Long primitives for that type.
  val timestampVectorMaker: VectorMaker = {
    case TimestampClass => ((b: ByteBuffer, len: Int) => FiloVector[Long](b, len))
  }

  val spark15vectorMaker = timestampVectorMaker orElse defaultVectorMaker
}

/**
 * A class to wrap the original FiloVector[Long] for Spark's InternalRow Timestamp representation,
 * which is based on microseconds rather than milliseconds.
 */
class SparkTimestampFiloVector(innerVector: FiloVector[Long]) extends FiloVector[Long] {
  final def isAvailable(index: Int): Boolean = innerVector.isAvailable(index)
  final def foreach[B](fn: Long => B): Unit = innerVector.foreach(fn)
  final def apply(index: Int): Long = innerVector(index) * 1000
  final def length: Int = innerVector.length
}

/**
 * Base the SparkRowReader on Spark's InternalRow.  Scans are much faster because
 * sources that return RDD[Row] need to be converted to RDD[InternalRow], and the
 * conversion is horribly expensive, especially for primitives.
 * The only downside is that the API is not stable.  :/
 * TODO: get UTF8String's out of Filo as well, so no string conversion needed :D
 */
class SparkRowReader(chunks: Array[ByteBuffer],
                     classes: Array[Class[_]],
                     emptyLen: Int = 0)
extends BaseGenericInternalRow with FiloRowReader {
  var rowNo: Int = -1
  final def setRowNo(newRowNo: Int): Unit = { rowNo = newRowNo }

  val parsers = FiloVector.makeVectors(chunks, classes, emptyLen, SparkRowReader.spark15vectorMaker)

  // Hack: wrap timestamp FiloVectors to give correct representation (microsecond-based)
  for { i <- 0 until chunks.size } {
    if (classes(i) == classOf[java.sql.Timestamp]) {
      parsers(i) = new SparkTimestampFiloVector(parsers(i).asInstanceOf[FiloVector[Long]])
    }
  }

  final def notNull(columnNo: Int): Boolean = parsers(columnNo).isAvailable(rowNo)

  override final def getBoolean(columnNo: Int): Boolean =
    parsers(columnNo).asInstanceOf[FiloVector[Boolean]](rowNo)
  override final def getInt(columnNo: Int): Int =
    parsers(columnNo).asInstanceOf[FiloVector[Int]](rowNo)
  override final def getLong(columnNo: Int): Long =
    parsers(columnNo).asInstanceOf[FiloVector[Long]](rowNo)
  override final def getDouble(columnNo: Int): Double =
    parsers(columnNo).asInstanceOf[FiloVector[Double]](rowNo)
  override final def getFloat(columnNo: Int): Float =
    parsers(columnNo).asInstanceOf[FiloVector[Float]](rowNo)

  // NOTE: This is horribly slow as it decodes a String from Filo's UTF8, then has
  // to convert back to string.  When Filo gets UTF8 support then use
  // UTF8String.fromAddress(object, offset, numBytes)... might enable zero copy strings
  override final def getUTF8String(columnNo: Int): UTF8String = {
    val str = parsers(columnNo).asInstanceOf[FiloVector[String]](rowNo)
    UTF8String.fromString(str)
  }

  final def getAny(columnNo: Int): Any = genericGet(columnNo)
  final def genericGet(columnNo: Int): Any =
    if (classes(columnNo) == classOf[String]) { getUTF8String(columnNo) }
    else { parsers(columnNo).boxed(rowNo) }

  override final def isNullAt(i: Int): Boolean = !notNull(i)
  def numFields: Int = parsers.length

  // Only used for pure select() queries, doesn't need to be fast?
  def copy(): InternalRow =
    new GenericInternalRow((0 until numFields).map(genericGet).toArray)
}
