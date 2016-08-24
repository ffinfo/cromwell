package cromwell.database.migration.metadata

import java.sql.{PreparedStatement, ResultSet, Statement}

import cromwell.database.liquibase.MultiJdbcConnection
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
  val FetchSize = Integer.MIN_VALUE
  val BatchSize = 50000
}

trait MetadataMigration extends CustomTaskChange {
  import MetadataMigration._

  val logger = LoggerFactory.getLogger("LiquibaseMetadataMigration")
  var batchCounter = 0

  protected def selectQuery: String

  /**
    * Migrate a row to the metadata table
    * @return number of insert statements added to the batch
    */
  protected def migrateRow(connection: JdbcConnection, statement: PreparedStatement, row: ResultSet, idx: Int): Int
  protected def filterCollectors: Boolean = true

  private def migrate(connection: MultiJdbcConnection, collectors: Set[Int]) = {
    logger.info("Executing SELECT")
    val selectStatement: Statement = connection.otherConnection.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY,
      java.sql.ResultSet.CONCUR_READ_ONLY)
    selectStatement.setFetchSize(FetchSize)
    val executionDataResultSet = selectStatement.executeQuery(selectQuery)
    logger.info("SELECT Done")

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
        batchCounter += migrateRow(connection, metadataInsertStatement, row, idx)

        if (batchCounter >= BatchSize) {
          logger.info("Executing batch and committing")
          metadataInsertStatement.executeBatch()
          connection.commit()
          batchCounter = 0
          logger.info("Committed")
        }
    }

    metadataInsertStatement.executeBatch()
    connection.commit()

    executionDataResultSet.close()
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
      val dbConn = database.getConnection.asInstanceOf[MultiJdbcConnection]
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
