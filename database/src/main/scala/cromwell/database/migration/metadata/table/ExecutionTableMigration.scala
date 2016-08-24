package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatementForCall, MetadataMigration}
import liquibase.database.jvm.JdbcConnection

/**
  * Transform data from the EXECUTION table to metadata
  */
class ExecutionTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
       SELECT CALL_FQN, EXECUTION.EXECUTION_ID, EXECUTION.STATUS, IDX, ATTEMPT,
         |  RC, EXECUTION.START_DT, EXECUTION.END_DT, BACKEND_TYPE, ALLOWS_RESULT_REUSE, WORKFLOW_EXECUTION_UUID, IFNULL(ra.ATTRIBUTE_VALUE >= ATTEMPT, NULL) as preemptible
         |FROM EXECUTION
         |  JOIN WORKFLOW_EXECUTION ON EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
         |  LEFT JOIN RUNTIME_ATTRIBUTES ra ON EXECUTION.EXECUTION_ID = ra.EXECUTION_ID AND ra.ATTRIBUTE_NAME = "preemptible"
         |                                     WHERE CALL_FQN NOT LIKE '%$%';""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Int = {
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
    // getBoolean returns false if the value is null - which means we need to check manually if the value was null so we don't add it if it was
    val preemptible = row.getBoolean("preemptible")
    if (!row.wasNull()) {
      metadataStatement.addKeyValue("preemptible", preemptible)
    }

    // Fields that we want regardless of whether or not information exists (if not it's an empty object)
    metadataStatement.addEmptyValue("outputs")
    metadataStatement.addEmptyValue("inputs")
    metadataStatement.addEmptyValue("runtimeAttributes")
    metadataStatement.addEmptyValue("executionEvents[]")

    metadataStatement.getBatchSize
  }

  override def getConfirmationMessage: String = "Execution Table migration complete."
}
