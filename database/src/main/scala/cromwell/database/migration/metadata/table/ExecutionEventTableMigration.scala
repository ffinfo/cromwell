package cromwell.database.migration.metadata.table

import java.sql.{ResultSet, PreparedStatement}

import cromwell.database.migration.metadata.{MetadataStatementForCall, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

class ExecutionEventTableMigration extends MetadataMigration {
  override protected def selectQuery: String =
    """
      |SELECT DESCRIPTION, EXECUTION.EXECUTION_ID, EXECUTION_EVENT.START_DT, EXECUTION_EVENT.END_DT, EXECUTION.CALL_FQN,
      |  EXECUTION.IDX, EXECUTION.ATTEMPT, EXECUTION.EXECUTION_ID, WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_UUID
      |FROM EXECUTION_EVENT
      |  LEFT JOIN EXECUTION ON EXECUTION.EXECUTION_ID = EXECUTION_EVENT.EXECUTION_ID
      |  LEFT JOIN WORKFLOW_EXECUTION ON WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID = EXECUTION.WORKFLOW_EXECUTION_ID;
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

      metadataStatement.addKeyValue(s"executionEvents[$idx]:description", row.getString("DESCRIPTION"))
      metadataStatement.addKeyValue(s"executionEvents[$idx]:startTime", row.getTimestamp("START_DT"))
      metadataStatement.addKeyValue(s"executionEvents[$idx]:endTime", row.getTimestamp("END_DT"))
    }
  }

  override def getConfirmationMessage: String = "ExecutionEvent Table migration complete."
}
