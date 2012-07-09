package org.tresql.metadata

import org.tresql.MetaData
import org.tresql._
import scala.collection.JavaConversions._
import java.sql.{ Connection => C }
import java.sql.ResultSet

//TODO all names perhaps should be stored in upper case?
//This class is thread safe i.e. instance can be used across multiple threads
class JDBCMetaData(private val db: String, private val defaultSchema: String = null,
    resources: Resources = Env)
  extends MetaData {
  val tables = new java.util.concurrent.ConcurrentHashMap[String, Table]
  val procedures = new java.util.concurrent.ConcurrentHashMap[String, Procedure]
  def dbName = db
  def table(name: String) = {
    val conn = resources.conn
    try {
      tables(name)
    } catch {
      case _: NoSuchElementException => {
        if (conn == null) throw new NullPointerException(
          """Connection not found in environment. Check if "Env update conn" (in this case statement execution must be done in the same thread) or "Env.sharedConn = conn" is called.""")
        val dmd = conn.getMetaData
        val rs = (if (dmd.storesUpperCaseIdentifiers) name.toUpperCase
        else name).split("\\.") match {
          case Array(t) => dmd.getTables(null,
            if (dmd.storesUpperCaseIdentifiers &&
              defaultSchema != null) defaultSchema.toUpperCase
            else defaultSchema, t, null)
          case Array(s, t) => dmd.getTables(null, s, t, null)
          case Array(c, s, t) => dmd.getTables(c, s, t, null)
        }
        var m = Set[(String, String)]()
        while (rs.next) {
          val schema = rs.getString("TABLE_SCHEM")
          val tableName = rs.getString("TABLE_NAME")
          m += Option(schema).getOrElse("<null>") -> tableName
          if (m.size > 1) {
            tables -= name
            rs.close
            throw new RuntimeException(
              "Ambiguous table name: " + name + "." + " Both " +
                m.map((t) => t._1 + "." + t._2).mkString(" and ") + " match")
          }
          val tableType = rs.getString("TABLE_TYPE")
          val remarks = rs.getString("REMARKS")
          val mdh = Map("name" -> tableName,
            "cols" -> cols(dmd.getColumns(null, schema, tableName, null)),
            "key" -> key(dmd.getPrimaryKeys(null, schema, tableName)),
            "refs" -> refs(dmd.getImportedKeys(null, schema, tableName)))
          tables += (name -> Table(mdh))
        }
        rs.close
        tables(name)
      }
    }
  }
  /* Implemented this way because did not understand scala concurrent compatibilities between
   * scala versions 2.9 and 2.10 */
  def tableOption(name:String) = try {
    Some(table(name))
  } catch {
    case _:NoSuchElementException => {
      None
    }
  }
  def procedure(name: String) = sys.error("Method not implemented")
  def procedureOption(name: String) = sys.error("Method not implemented")

  def cols(rs: ResultSet) = {
    import scala.collection.mutable.ListBuffer
    val l: ListBuffer[Map[String, String]] = ListBuffer()
    while (rs.next) {
      val name = rs.getString("COLUMN_NAME")
      val typeInt = rs.getInt("DATA_TYPE")
      val typeName = rs.getString("TYPE_NAME")
      val size = rs.getInt("COLUMN_SIZE")
      val decDig = rs.getInt("DECIMAL_DIGITS")
      val nullable = rs.getInt("NULLABLE")
      val comments = rs.getString("REMARKS")
      l += Map("name" -> name, "type" -> typeName)
    }
    rs.close
    l.toList
  }
  def key(rs: ResultSet) = {
    var cols: List[String] = Nil
    while (rs.next) {
      val colName = rs.getString("COLUMN_NAME")
      val keySeq = rs.getShort("KEY_SEQ")
      val pkName = rs.getString("PK_NAME")
      cols = cols :+ colName
    }
    rs.close
    cols
  }
  def refs(rs: ResultSet) = {
    import scala.collection.mutable.ListBuffer
    var prevTable = null
    val l: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, Any]] =
      scala.collection.mutable.Map()
    var trfsm: scala.collection.mutable.Map[String, Any] = null
    var trfs: ListBuffer[ListBuffer[String]] = null
    var crfs: ListBuffer[String] = null
    while (rs.next) {
      val pkTable = rs.getString("PKTABLE_NAME");
      val fkColName = rs.getString("FKCOLUMN_NAME");
      val pkColName = rs.getString("PKCOLUMN_NAME");
      val keySeq = rs.getShort("KEY_SEQ");
      val fkName = rs.getString("FK_NAME");
      if (pkTable != prevTable) {
        try {
          trfs = l(pkTable)("refs").asInstanceOf[ListBuffer[ListBuffer[String]]]
        } catch {
          case _: NoSuchElementException => {
            trfs = ListBuffer()
            trfsm = scala.collection.mutable.Map("table" -> pkTable, "refs" -> trfs)
            l += (pkTable -> trfsm)
          }
        }
      }
      if (keySeq == 1) {
        crfs = ListBuffer()
        trfs += crfs
      }
      crfs += fkColName
    }
    rs.close
    (l.values.map { t =>
      Map("table" -> t("table"),
        "refs" -> (t("refs").asInstanceOf[ListBuffer[ListBuffer[String]]] map
          { _.toList }).toList)
    }).toList
  }
}

object JDBCMetaData {
  def apply(db: String, defaultSchema: String = null, resources: Resources = Env) = {
    new JDBCMetaData(db, defaultSchema, resources)
  }
}