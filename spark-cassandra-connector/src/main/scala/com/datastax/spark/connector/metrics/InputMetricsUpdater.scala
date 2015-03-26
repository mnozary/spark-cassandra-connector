package com.datastax.spark.connector.metrics

import com.codahale.metrics.Timer
import com.datastax.driver.core.Row
import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.executor.{DataReadMethod, InputMetrics}
import org.apache.spark.metrics.CassandraConnectorSource

private[connector] trait InputMetricsUpdater extends MetricsUpdater {
  def resultSetFetchTimer: Option[Timer]

  def updateMetrics(row: Row): Row
}

private class DetailedInputMetricsUpdater(metrics: InputMetrics, groupSize: Int) extends InputMetricsUpdater {
  require(groupSize > 0)

  val resultSetFetchTimer = Some(CassandraConnectorSource.readPageWaitTimer)

  private val taskTimer = CassandraConnectorSource.readTaskTimer.time()

  private var cnt = 0
  private var dataLength = metrics.bytesRead

  def updateMetrics(row: Row): Row = {
    for (i <- 0 until row.getColumnDefinitions.size() if !row.isNull(i))
      metrics.bytesRead += row.getBytesUnsafe(i).remaining()

    cnt += 1
    if (cnt == groupSize)
      update()
    row
  }

  @inline
  private def update(): Unit = {
    CassandraConnectorSource.readRowMeter.mark(cnt)
    CassandraConnectorSource.readByteMeter.mark(metrics.bytesRead - dataLength)
    dataLength = metrics.bytesRead
    cnt = 0
  }

  def finish(): Long = {
    update()
    val t = taskTimer.stop()
    forceReport()
    t
  }
}

private class DummyInputMetricsUpdater extends InputMetricsUpdater {
  private val taskTimer = System.nanoTime()

  val resultSetFetchTimer = None

  def updateMetrics(row: Row): Row = row

  def finish(): Long = {
    System.nanoTime() - taskTimer
  }
}

object InputMetricsUpdater {
  val InputMetricsEnabledProperty = "spark.cassandra.input.metrics"

  val Properties = Seq(
    InputMetricsEnabledProperty
  )

  lazy val detailedMetricsEnabled =
    SparkEnv.get.conf.getBoolean(InputMetricsEnabledProperty, defaultValue = true)

  def apply(taskContext: TaskContext, groupSize: Int): InputMetricsUpdater = {
    CassandraConnectorSource.ensureInitialized

    if (detailedMetricsEnabled) {
      val tm = taskContext.taskMetrics()
      if (tm.inputMetrics.isEmpty || tm.inputMetrics.get.readMethod != DataReadMethod.Hadoop)
        tm.inputMetrics = Some(new InputMetrics(DataReadMethod.Hadoop))

      new DetailedInputMetricsUpdater(tm.inputMetrics.get, groupSize)
    } else {
      new DummyInputMetricsUpdater
    }
  }
}