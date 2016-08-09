package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatementForCall, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

class ExecutionInfoTableMigration extends MetadataMigration {
  val logPrefix = "$log_"

  override protected def selectQuery: String =
    """
      |SELECT INFO_KEY, EXECUTION.EXECUTION_ID, INFO_VALUE, CALL_FQN, IDX, ATTEMPT, WORKFLOW_EXECUTION_UUID
      |FROM EXECUTION_INFO
      |  LEFT JOIN EXECUTION ON EXECUTION_INFO.EXECUTION_ID = EXECUTION.EXECUTION_ID
      |  LEFT JOIN WORKFLOW_EXECUTION ON EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
      |WHERE CALL_FQN NOT LIKE "%$%";
    """.stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    if (!collectors.contains(row.getInt("EXECUTION_ID"))) {
      val metadataStatement = new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        row.getString("CALL_FQN"),
        row.getInt("IDX"),
        row.getInt("ATTEMPT")
      )

      infoKeyToMetadataKey(row.getString("INFO_KEY")) foreach { key =>
        metadataStatement.addKeyValue(key, row.getString("INFO_VALUE"))
      }
    }
  }

  private def infoKeyToMetadataKey(key: String) = key match {
    case "$log_stdout" => Option("stdout")
    case "$log_stderr" => Option("stderr")
    case k if k.startsWith(logPrefix) =>
      val logName = k.substring(logPrefix.length)
      Option(s"backendLogs:$logName")
    case "JES_RUN_ID" => Option("jobId")
    case "JES_STATUS" => Option("backendStatus")
    case "SGE_JOB_NUMBER" | "LSF_JOB_NUMBER" => Option("jobNumber")
    case k =>
      logger.warn(s"Unrecognized EXECUTION_INFO key: $k")
      None
  }

  override def getConfirmationMessage: String = "ExecutionInfo Table migration complete."
}
