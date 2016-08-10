package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatementForCall}
import liquibase.database.jvm.JdbcConnection

class RuntimeAttributesTableMigration extends MetadataMigration {
  override protected def selectQuery: String =
    """
      SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE, WORKFLOW_EXECUTION_UUID, CALL_FQN, IDX, ATTEMPT, EXECUTION.EXECUTION_ID
      |FROM RUNTIME_ATTRIBUTES
      |     LEFT JOIN EXECUTION ON RUNTIME_ATTRIBUTES.EXECUTION_ID = EXECUTION.EXECUTION_ID
      |     LEFT JOIN WORKFLOW_EXECUTION ON EXECUTION.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID;
    """.stripMargin

  override protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    val statementForCall = new MetadataStatementForCall(
      statement,
      row.getString("WORKFLOW_EXECUTION_UUID"),
      row.getString("CALL_FQN"),
      row.getInt("IDX"),
      row.getInt("ATTEMPT")
    )

    val attributeName = row.getString("ATTRIBUTE_NAME")
    val attributeValue = row.getString("ATTRIBUTE_VALUE")

    statementForCall.addKeyValue(s"runtimeAttributes:$attributeName", attributeValue)
  }

  override def getConfirmationMessage: String = "RuntimeAttributes Table migration complete."
}
