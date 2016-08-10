package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.MetadataStatementForWorkflow
import wdl4s.values._

class WorkflowOutputSymbolTableMigration extends SymbolTableMigration {
  override protected def selectQuery: String = """
     SELECT SYMBOL.SCOPE, SYMBOL.`INDEX`, SYMBOL.NAME, IO, WDL_TYPE, WDL_VALUE, WORKFLOW_EXECUTION_UUID
       |FROM SYMBOL
       |  JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID # Joins the workflow UUID
       |WHERE SYMBOL.IO = 'OUTPUT' AND SYMBOL.`INDEX` = -1 AND SYMBOL.REPORTABLE_RESULT = 1;
       |""".stripMargin

  override def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue) = {
    val scope = row.getString("SCOPE")
    val name = row.getString("NAME")

    val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
    addWdlValue(s"outputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
  }

  override def getConfirmationMessage: String = "Workflow outputs from Symbol Table migration complete."
}
