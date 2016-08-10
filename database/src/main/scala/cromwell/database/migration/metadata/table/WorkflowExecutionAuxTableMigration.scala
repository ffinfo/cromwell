package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatementForWorkflow}
import liquibase.database.jvm.JdbcConnection
import spray.json.JsValue
import spray.json._

class WorkflowExecutionAuxTableMigration extends MetadataMigration {
  override protected def selectQuery: String = """
        SELECT JSON_INPUTS, WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_UUID
         |FROM WORKFLOW_EXECUTION_AUX
         |  LEFT JOIN WORKFLOW_EXECUTION
         |    ON WORKFLOW_EXECUTION.WORKfLOW_EXECUTION_ID = WORKFLOW_EXECUTION_AUX.WORKFLOW_EXECUTION_ID;""".stripMargin

  override protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                                    statement: PreparedStatement, row: ResultSet, idx: Int): Unit = {
    val inputs = row.getString("JSON_INPUTS").parseJson

    val metadataStatement = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
  }

  def addJson(jsValue: JsValue, metadataStatement: MetadataStatementForWorkflow) = {

  }

  override def getConfirmationMessage: String = "WorkflowExecution Table migration complete."
}
