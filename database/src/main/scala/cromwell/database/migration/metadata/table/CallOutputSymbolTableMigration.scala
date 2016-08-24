package cromwell.database.migration.metadata.table

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.metadata.MetadataStatementForCall
import wdl4s.values._

class CallOutputSymbolTableMigration extends SymbolTableMigration {
  override protected def selectQuery: String = """
     SELECT SYMBOL.SCOPE, SYMBOL.NAME, ex.IDX, IO, WDL_TYPE,
|          WDL_VALUE, WORKFLOW_EXECUTION_UUID, ex.EXECUTION_ID, REPORTABLE_RESULT,
|          MAX(ex.ATTEMPT) as attempt
|        FROM SYMBOL
|          JOIN WORKFLOW_EXECUTION ON SYMBOL.WORKFLOW_EXECUTION_ID = WORKFLOW_EXECUTION.WORKFLOW_EXECUTION_ID
|          LEFT JOIN EXECUTION ex
|            ON SYMBOL.WORKFLOW_EXECUTION_ID = ex.WORKFLOW_EXECUTION_ID
|            AND SYMBOL.SCOPE = ex.CALL_FQN
|               AND SYMBOL.IDX = ex.IDX
|        WHERE SYMBOL.IO = 'OUTPUT'
|          GROUP BY ex.CALL_FQN, ex.IDX, SYMBOL.NAME, SYMBOL.WORKFLOW_EXECUTION_ID;""".stripMargin

  override def processSymbol(statement: PreparedStatement, row: ResultSet, idx: Int, wdlValue: WdlValue): Int = {
    val scope = row.getString("SCOPE")
    val name = row.getString("NAME")
    val index = row.getInt("IDX")

    // Add outputs only to the last attempt
    val metadataStatementForCall = new MetadataStatementForCall(statement,
      row.getString("WORKFLOW_EXECUTION_UUID"),
      scope,
      index,
      row.getInt("attempt")
    )

    addWdlValue(s"outputs:$name", wdlValue, metadataStatementForCall)
    metadataStatementForCall.getBatchSize
  }

  override def getConfirmationMessage: String = "Call outputs from Symbol Table migration complete."
}
