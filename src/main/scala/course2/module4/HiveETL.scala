package course2.module4

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.scheduler.{
  StreamingListener, StreamingListenerReceiverError, StreamingListenerReceiverStopped}
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}
import data.Flight
import util.Files
import scala.util.control.NonFatal

/**
 * Sketch of an ETL pipeline into Hive using Spark Streaming.
 * Data is written on a socket running in one thread while a Spark Streaming
 * app runs in another set of threads to process the incoming data.
 */
object HiveETL {

  val defaultPort = 9000
  val interval = Seconds(5)
  val pause = 10  // milliseconds
  val server = "127.0.0.1"
  val checkpointDir = "output/checkpoint_dir"
  val runtime = 10 * 1000   // run for N*1000 milliseconds
  val numRecordsToWritePerBlock = 10000

  def main(args: Array[String]): Unit = {
    val port = if (args.size > 0) args(0).toInt else defaultPort
    val conf = new SparkConf()
    conf.setMaster("local[*]")
    conf.setAppName("Spark Streaming and Hive")
    conf.set("spark.sql.shuffle.partitions", "4")
    conf.set("spark.app.id", "Aggs")
    val sc = new SparkContext(conf)

    // Clean up the checkpoint directory and the in-memory metastore from
    // the last run, if any.
    // Don't do any of this in production, under normal circumstances!
    Files.rmrf("derby.log")
    Files.rmrf("metastore_db")
    Files.rmrf(checkpointDir)

    def createContext(): StreamingContext = {
      val ssc = new StreamingContext(sc, interval)
      ssc.checkpoint(checkpointDir)

      val dstream = readSocket(ssc, server, port)
      processDStream(ssc, dstream)
    }

    // Declared as a var so we can see it in the following try/catch/finally blocks.
    var ssc: StreamingContext = null
    var dataThread: Thread = null

    try {

      banner("Creating source socket:")
      dataThread = startSocketDataThread(port)

      // The preferred way to construct a StreamingContext, because if the
      // program is restarted, e.g., due to a crash, it will pick up where it
      // left off. Otherwise, it starts a new job.
      ssc = StreamingContext.getOrCreate(checkpointDir, createContext _)
      ssc.addStreamingListener(new EndOfStreamListener(ssc, dataThread))
      ssc.start()
      ssc.awaitTerminationOrTimeout(runtime)

    } finally {
      shutdown(ssc, dataThread)
    }
  }

  def readSocket(ssc: StreamingContext, server: String, port: Int): DStream[String] =
    try {
      banner(s"Connecting to $server:$port...")
      ssc.socketTextStream(server, port)
    } catch {
      case th: Throwable =>
        ssc.stop()
        throw new RuntimeException(
          s"Failed to initialize server:port socket with $server:$port:",
          th)
    }

  import java.net.{Socket, ServerSocket}
  import java.io.{File, PrintWriter}

  def makeRunnable(port: Int) = new Runnable {
    def run() = {
      val listener = new ServerSocket(port);
      var socket: Socket = null
      try {
        val socket = listener.accept()
        val out = new PrintWriter(socket.getOutputStream(), true)
        val inputPath = "data/airline-flights/alaska-airlines/2008.csv"
        // A hack to write N lines at a time, then sleep...
        var lineCount = 0
        var passes = 0
        scala.io.Source.fromFile(inputPath).getLines().foreach {line =>
          out.println(line)
          if (lineCount % numRecordsToWritePerBlock == 0) Thread.sleep(pause)
          lineCount += 1
        }
      } finally {
        listener.close();
        if (socket != null) socket.close();
      }
    }
  }

  def startSocketDataThread(port: Int): Thread = {
    val dataThread = new Thread(makeRunnable(port))
    dataThread.start()
    dataThread
  }

  def processDStream(ssc: StreamingContext, dstream: DStream[String]): StreamingContext = {
    val hiveContext = new HiveContext(ssc.sparkContext)
    import hiveContext.implicits._
    import hiveContext.sql
    import org.apache.spark.sql.functions._  // for min, max, etc.

    // We'll use the flights data. We're going to do a little more work
    // than necessary; Hive could partition creation for us, for example,
    // if we use INSERT INTO, with values for new partition columns, etc.

    // Before processing the stream, create the table.
    val hiveETLDir = new java.io.File("output/hive-etl")
    // Hive DDL statements require absolute paths:
    val hiveETLPath = hiveETLDir.getCanonicalPath
    banner(s"""
      Create an 'external', partitioned Hive table for a subset of the flight data:
      Location: $hiveETLPath
      """)

    sql(s"""
      CREATE EXTERNAL TABLE IF NOT EXISTS flights2 (
        depTime        INT,
        arrTime        INT,
        uniqueCarrier  STRING,
        flightNum      INT,
        origin         STRING,
        dest           STRING)
      PARTITIONED BY (
        depYear        STRING,
        depMonth       STRING,
        depDay         STRING)
      ROW FORMAT DELIMITED FIELDS TERMINATED BY '|'
      location '$hiveETLPath'
      """).show
    println("Tables:")
    sql("SHOW TABLES").show

    dstream.foreachRDD { (rdd, timestamp) =>
      try {
        val flights =
          rdd.flatMap(line => Flight.parse(line)).cache
        val uniqueYMDs = flights.map(f =>
          (f.date.year, f.date.month, f.date.dayOfMonth))
          .distinct().collect()  // collect returns an Array
        uniqueYMDs.foreach { case (y,m,d) =>
          val yStr = "%04d".format(y)
          val mStr = "%02d".format(m)
          val dStr = "%02d".format(d)
          val partitionPath ="%s/%s-%s-%s".format(
            hiveETLPath, yStr, mStr, dStr)
          println(s"(time: $timestamp) Creating partition: $partitionPath")
          sql(s"""
            ALTER TABLE flights2 ADD IF NOT EXISTS PARTITION (
              depYear='$yStr', depMonth='$mStr', depDay='$dStr')
            LOCATION '$partitionPath'
            """)
          flights.filter(f =>
              f.date.year == y &&
              f.date.month == m &&
              f.date.dayOfMonth == d)
            .map(f =>
              // DON'T write the partition columns.
              Seq(f.times.depTime, f.times.arrTime, f.uniqueCarrier,
                f.flightNum, f.origin, f.dest).mkString("|"))
            .saveAsTextFile(partitionPath)
        }
        val showp = sql("SHOW PARTITIONS flights2")
        val showpCount = showp.count
        println(s"(time: $timestamp) Partitions (${showpCount}):")
        showp.foreach(p => println("  "+p))
      } catch {
        case NonFatal(ex) =>
          banner("Exception: "+ex)
          sys.exit(1)
      }
    }
    ssc
  }

  protected def banner(message: String): Unit = {
    println("\n*********************\n")
    message.split("\n").foreach(line => println("    "+line))
    println("\n*********************\n")
  }

  protected def shutdown(ssc: StreamingContext, dataThread: Thread) = {
    banner("Shutting down...")
    if (dataThread != null) dataThread.interrupt()
    else ("The dataThread is null!")
    if (ssc != null) ssc.stop(stopSparkContext = true, stopGracefully = true)
    else ("The StreamingContext is null!")
  }

  class EndOfStreamListener(ssc: StreamingContext, dataThread: Thread) extends StreamingListener {
    override def onReceiverError(error: StreamingListenerReceiverError):Unit = {
      banner(s"Receiver Error: $error. Stopping...")
      shutdown(ssc, dataThread)
    }
    override def onReceiverStopped(stopped: StreamingListenerReceiverStopped):Unit = {
      banner(s"Receiver Stopped: $stopped. Stopping...")
      shutdown(ssc, dataThread)
    }
  }
}
