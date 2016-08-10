package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatementForWorkflow, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

class WorkflowExecutionTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
        SELECT WORKFLOW_EXECUTION_UUID, STATUS, START_DT, END_DT, WORKFLOW_NAME
          FROM WORKFLOW_EXECUTION;""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    val startDt = row.getTimestamp("START_DT")

    val metadataStatement = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))

    metadataStatement.addKeyValue("submission", startDt)
    metadataStatement.addKeyValue("start", startDt)
    metadataStatement.addKeyValue("end", row.getTimestamp("END_DT"))
    metadataStatement.addKeyValue("status", row.getString("STATUS"))
    metadataStatement.addKeyValue("workflowName", row.getString("WORKFLOW_NAME"))
    metadataStatement.addEmptyValue("outputs")
  }

  override def getConfirmationMessage: String = "WorkflowExecution Table migration complete."
}
