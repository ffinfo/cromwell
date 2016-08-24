package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataStatementForCall, MetadataStatementForWorkflow}
import wdl4s.values._

class InputSymbolTableMigration extends SymbolTableMigration {
  override protected def selectQuery: String = """
   SELECT SYMBOL.SCOPE, SYMBOL.NAME, ex.IDX, ex.ATTEMPT, IO, WDL_TYPE,
     |  WDL_VALUE, WORKFLOW_EXECUTION_UUID, REPORTABLE_RESULT, ex.EXECUTION_ID, SYMBOL.WORKFLOW_EXECUTION_ID
     |FROM SYMBOL
     |  JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
     |  LEFT JOIN EXECUTION ex ON ex.WORKFLOW_EXECUTION_ID = SYMBOL.WORKFLOW_EXECUTION_ID AND SYMBOL.SCOPE = ex.CALL_FQN
     |WHERE IO = 'INPUT'""".stripMargin

  override def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue): Int = {
    val name = row.getString("NAME")
    val scope = row.getString("SCOPE")
    val index = row.getString("IDX")
    val attempt = row.getInt("ATTEMPT")

    if (index != null) {
      // Call scoped
      val metadataStatementForCall = new MetadataStatementForCall(statement,
        row.getString("WORKFLOW_EXECUTION_UUID"),
        scope,
        row.getInt("IDX"),
        attempt
      )

      addWdlValue(s"inputs:$name", wdlValue, metadataStatementForCall)
      metadataStatementForCall.getBatchSize
    } else if (!scope.contains('.')) { // Only add workflow level inputs with single-word FQN
      // Workflow scoped
      val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
      addWdlValue(s"inputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
      metadataStatementForWorkflow.getBatchSize

    } else 0
  }

  override def getConfirmationMessage: String = "Inputs from Symbol Table migration complete."
}
