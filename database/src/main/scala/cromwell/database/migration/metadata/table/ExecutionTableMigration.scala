package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatementForCall, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

/**
  * Transform data from the EXECUTION table to metadata
  */
class ExecutionTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
       SELECT CALL_FQN, EXECUTION.EXECUTION_ID, EXECUTION.STATUS, IDX, ATTEMPT, RC, EXECUTION.START_DT, EXECUTION.END_DT, BACKEND_TYPE, ALLOWS_RESULT_REUSE, WORKFLOW_EXECUTION_UUID
         FROM EXECUTION
           LEFT JOIN WORKFLOW_EXECUTION ON EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
         WHERE CALL_FQN NOT LIKE '%$%';""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    if (!collectors.contains(row.getInt("EXECUTION_ID"))) {
      val attempt: Int = row.getInt("ATTEMPT")

      val metadataStatement = new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        row.getString("CALL_FQN"),
        row.getInt("IDX"),
        attempt
      )

      val returnCode = row.getString("RC") // Allows for it to be null

      metadataStatement.addKeyValue("start", row.getTimestamp("START_DT"))
      metadataStatement.addKeyValue("backend", row.getString("BACKEND_TYPE"))
      metadataStatement.addKeyValue("end", row.getTimestamp("END_DT"))
      metadataStatement.addKeyValue("executionStatus", row.getString("STATUS"))
      metadataStatement.addKeyValue("returnCode", if (returnCode != null) returnCode.toInt else null)
      metadataStatement.addKeyValue("cache:allowResultReuse", row.getBoolean("ALLOWS_RESULT_REUSE"))

      // Fields that we want regardless of whether or not information exists (if not it's an empty object)
      metadataStatement.addEmptyValue("outputs")
      metadataStatement.addEmptyValue("inputs")
      metadataStatement.addEmptyValue("runtimeAttributes")
      metadataStatement.addEmptyValue("executionEvents[]")

      migratePreemptibleField(connection, row.getInt("EXECUTION_ID"), attempt, metadataStatement)
    }
  }

  def migratePreemptibleField(connection: JdbcConnection, executionId: Int, attempt: Int, statementForCall: MetadataStatementForCall) = {
    val preemptibleValueQuery = s"""
      SELECT ATTRIBUTE_VALUE, WORKFLOW_EXECUTION_UUID, CALL_FQN, IDX, ATTEMPT
        FROM RUNTIME_ATTRIBUTES, EXECUTION, WORKFLOW_EXECUTION
        WHERE RUNTIME_ATTRIBUTES.EXECUTION_ID = EXECUTION.EXECUTION_ID
          AND EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
          AND ATTRIBUTE_NAME = "preemptible"
          AND RUNTIME_ATTRIBUTES.EXECUTION_ID = $executionId;
    """.stripMargin

    val preemptibleRS = connection.createStatement().executeQuery(preemptibleValueQuery)
    if (preemptibleRS.next()) {
      val maxPreemption = preemptibleRS.getString("ATTRIBUTE_VALUE").toInt
      statementForCall.addKeyValue("preemptible", maxPreemption > attempt)
    }
  }

  override def getConfirmationMessage: String = "Execution Table migration complete."
}
