package cromwell.database.migration.metadata.table

import java.sql.{ResultSet, PreparedStatement}

import cromwell.database.migration.metadata.{MetadataStatementForWorkflow, MetadataStatementForCall, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

class FailureEventTableMigration extends MetadataMigration {
  override protected def selectQuery: String =
    """
      |SELECT FAILURE_EVENT.EVENT_MESSAGE, FAILURE_EVENT.EVENT_TIMESTAMP,
      |  EXECUTION.CALL_FQN, EXECUTION.IDX, EXECUTION.ATTEMPT,
      |  WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_UUID
      |FROM FAILURE_EVENT
      |  LEFT JOIN WORKFLOW_EXECUTION ON WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID = FAILURE_EVENT.WORKFLOW_EXECUTION_ID
      |  LEFT JOIN EXECUTION ON EXECUTION.EXECUTION_ID = FAILURE_EVENT.EXECUTION_ID;
    """.stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {

    val metadataStatement = if (row.getString("CALL_FQN") != null) {
      // Call failure
      new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        row.getString("CALL_FQN"),
        row.getInt("IDX"),
        row.getInt("ATTEMPT")
      )
    } else {
      // Workflow failure
      new MetadataStatementForWorkflow(
        statement,
        row.getString("WORKFLOW_EXECUTION_UUID")
      )
    }

    metadataStatement.addKeyValue(s"failures[$idx]:failure", row.getString("EVENT_MESSAGE"))
    metadataStatement.addKeyValue(s"failures[$idx]:timestamp", row.getTimestamp("EVENT_TIMESTAMP"))
  }

  override def getConfirmationMessage: String = "FailureEvent Table migration complete."
}
