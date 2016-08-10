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
import scala.util.{Success, Try}

object MetadataMigration {
  var collectors: Set[Int] = _
}

trait MetadataMigration extends CustomTaskChange {
  val logger = LoggerFactory.getLogger("LiquibaseMetadataMigration")

  protected def selectQuery: String
  protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Unit
  protected def filterCollectors: Boolean = true

  private def migrate(connection: JdbcConnection, collectors: Set[Int]) = {
    val executionDataResultSet = connection.createStatement().executeQuery(selectQuery)
    val metadataInsertStatement = MetadataStatement.makeStatement(connection)

    val executionIterator = new ResultSetIterator(executionDataResultSet)

    // Filter collectors out if needed
    val filtered = if (filterCollectors) {
      executionIterator filter { row =>
        // Assumes that, if it does, every selectQuery returns the Execution Id with this column name
        Try(row.getString("EXECUTION_ID")) match {
          case Success(executionId) => executionId == null || !collectors.contains(executionId.toInt)
          case _ => true
        }
      }
    } else executionIterator

    filtered.zipWithIndex foreach {
      case (row, idx) =>
        migrateRow(connection, metadataInsertStatement, row, idx)
        if (idx % 100 == 0) {
          metadataInsertStatement.executeBatch()
          connection.commit()
        }
    }

    metadataInsertStatement.executeBatch()
    connection.commit()
  }

  private var resourceAccessor: ResourceAccessor = _

  private def getCollectors(connection: JdbcConnection) = {
    if (MetadataMigration.collectors != null) MetadataMigration.collectors
    else {
      MetadataMigration.collectors = findCollectorIds(connection)
      MetadataMigration.collectors
    }
  }

  /** We want to exclude collectors from metadata entirely.
    * This method finds their Ids so they can be passed to the migration code that can decide how to act upon them.
    */
  private def findCollectorIds(connection: JdbcConnection) = {
    val collectorsIdQuery =
      """
        SELECT EXECUTION_ID
        |FROM EXECUTION
        |   JOIN(SELECT CALL_FQN, ATTEMPT, WORKFLOW_EXECUTION_ID
        |      FROM EXECUTION
        |    GROUP BY CALL_FQN, ATTEMPT, WORKFLOW_EXECUTION_ID
        |    HAVING COUNT(*) > 1
        |    ) collectors
        |ON collectors.CALL_FQN = EXECUTION.CALL_FQN
        |   AND collectors.ATTEMPT = EXECUTION.ATTEMPT
        |   AND collectors.WORKFLOW_EXECUTION_ID = EXECUTION.WORKFLOW_EXECUTION_ID
        |WHERE EXECUTION.IDX = -1
      """.stripMargin

    val collectorsRS = new ResultSetIterator(connection.createStatement().executeQuery(collectorsIdQuery))
    collectorsRS map { _.getInt("EXECUTION_ID") } toSet
  }

  override def execute(database: Database): Unit = {
    try {
      val dbConn = database.getConnection.asInstanceOf[JdbcConnection]
      dbConn.setAutoCommit(false)
      migrate(dbConn, getCollectors(dbConn))
    } catch {
      case t: CustomChangeException => throw t
      case t: Throwable => throw new CustomChangeException(s"Could not apply migration script for metadata at ${getClass.getSimpleName}", t)
    }
  }

  override def setUp(): Unit = ()

  override def validate(database: Database): ValidationErrors = new ValidationErrors

  override def setFileOpener(resourceAccessor: ResourceAccessor): Unit = {
    this.resourceAccessor = resourceAccessor
  }
}
