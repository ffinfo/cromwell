package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.{MetadataMigration, MetadataStatement, MetadataStatementForCall, MetadataStatementForWorkflow}
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.CustomChangeException
import wdl4s.types.{WdlPrimitiveType, WdlType}
import wdl4s.values._

import scala.util.{Failure, Success, Try}

class InputSymbolTableMigration extends SymbolTableMigration {
  override protected def selectQuery: String = """
   SELECT SYMBOL.SCOPE, SYMBOL.NAME, ex.IDX, ex.ATTEMPT, IO, WDL_TYPE,
   |  WDL_VALUE, WORKFLOW_EXECUTION_UUID, REPORTABLE_RESULT, ex.EXECUTION_ID, SYMBOL.WORKFLOW_EXECUTION_ID
   |FROM SYMBOL
   |  LEFT JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID # Joins the workflow UUID
   |  LEFT JOIN(
   |    SELECT CALL_FQN, IDX, ATTEMPT, WORKFLOW_EXECUTION_ID, EXECUTION_ID
   |    FROM EXECUTION
   |  ) ex
   |  ON SYMBOL.WORKFLOW_EXECUTION_ID = ex.WORKFLOW_EXECUTION_ID
   |  AND SYMBOL.SCOPE = ex.CALL_FQN
   |WHERE IO = 'INPUT';""".stripMargin

  override def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue) = {
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
    } else if (!scope.contains('.')) { // Only add workflow level inputs with single-word FQN
      // Workflow scoped
      val metadataStatementForWorkflow = new MetadataStatementForWorkflow(statement, row.getString("WORKFLOW_EXECUTION_UUID"))
      addWdlValue(s"inputs:$scope.$name", wdlValue, metadataStatementForWorkflow)
    }
  }

  override def getConfirmationMessage: String = "Inputs from Symbol Table migration complete."
}
