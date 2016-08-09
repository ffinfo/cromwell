package cromwell.database.migration.metadata

import java.sql.{PreparedStatement, ResultSet}

import cromwell.database.migration.ResultSetIterator
import liquibase.change.custom.CustomTaskChange
import liquibase.database.Database
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.{CustomChangeException, ValidationErrors}
import liquibase.resource.ResourceAccessor
import org.slf4j.LoggerFactory

import scala.language.postfixOps

trait MetadataMigration extends CustomTaskChange {
  val logger = LoggerFactory.getLogger("LiquibaseMetadataMigration")

  protected def selectQuery: String
  protected def migrateRow(connection: JdbcConnection, collectors: Set[Int],
                           statement: PreparedStatement, row: ResultSet, idx: Int): Unit

  private def migrate(connection: JdbcConnection, collectors: Set[Int]) = {
    val executionDataResultSet = connection.createStatement().executeQuery(selectQuery)
    val metadataInsertStatement = MetadataStatement.makeStatement(connection)

    val executionIterator = new ResultSetIterator(executionDataResultSet)

    executionIterator.zipWithIndex foreach {
      case (row, idx) =>
        migrateRow(connection, collectors, metadataInsertStatement, row, idx)
        if (idx % 100 == 0) {
          metadataInsertStatement.executeBatch()
          connection.commit()
        }
    }

    metadataInsertStatement.executeBatch()
    connection.commit()
  }

  private var resourceAccessor: ResourceAccessor = null

  private def findCollectorIds(connection: JdbcConnection) = {
    val collectorsIdQuery =
      """
        |SELECT EXECUTION_ID
        |FROM EXECUTION
        |GROUP BY CALL_FQN, ATTEMPT, WORKFLOW_EXECUTION_ID
        |HAVING COUNT(*) > 1
      """.stripMargin

    val collectorsRS = new ResultSetIterator(connection.createStatement().executeQuery(collectorsIdQuery))
    collectorsRS map { _.getInt("EXECUTION_ID") } toSet
  }

  override def execute(database: Database): Unit = {
    val dbConn = database.getConnection.asInstanceOf[JdbcConnection]
    try {
      dbConn.setAutoCommit(false)
      migrate(dbConn, findCollectorIds(dbConn))
    } catch {
      case t: CustomChangeException => throw t
      case t: Throwable => throw new CustomChangeException("Could not apply migration script for metadata", t)
    }
  }

  override def setUp(): Unit = { }

  override def validate(database: Database): ValidationErrors = {
    new ValidationErrors
  }

  override def setFileOpener(resourceAccessor: ResourceAccessor): Unit = {
    this.resourceAccessor = resourceAccessor
  }
}
