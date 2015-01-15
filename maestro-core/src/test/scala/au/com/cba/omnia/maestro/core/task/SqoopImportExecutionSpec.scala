//   Copyright 2014 Commonwealth Bank of Australia
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package au.com.cba.omnia.maestro.core.task

import java.io.{File, InputStream}
import java.util.UUID

import scala.util.Failure

import scalaz.effect.IO

import scalikejdbc.{SQL, AutoSession, ConnectionPool}

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

import org.specs2.specification.BeforeExample

import au.com.cba.omnia.parlour.SqoopSyntax.{ParlourImportDsl,TeradataParlourImportDsl}

import au.com.cba.omnia.thermometer.fact.Fact
import au.com.cba.omnia.thermometer.fact.PathFactoids._
import au.com.cba.omnia.thermometer.context.Context
import au.com.cba.omnia.thermometer.tools.Streams
import au.com.cba.omnia.thermometer.core.{Thermometer, ThermometerRecordReader, ThermometerSpec}, Thermometer._

object SqoopImportExecutionSpec
  extends ThermometerSpec
  with BeforeExample
  with SqoopExecution { def is = s2"""
  Sqoop Import Execution test
  ===========================

  imports data from DB to HDFS successfully           $endToEndImportWithSuccess
  imports data from DB to HDFS using SQL query        $endToEndImportWithSqlQuery
  handles exceptions while importing data             $endToEndImportWithException
  handles exceptions while table not set              $endToEndImportWithoutTable
  can import data from two tables in the same session $doubleImport
  fails with Teradata connection manager       $endToEndImportWithTeradataConnMan
  succeeds with Teradata reset conn-manager    $endToEndImportWithTeradataResetConnMan
"""
  val connectionString = "jdbc:hsqldb:mem:sqoopdb"
  val username         = "sa"
  val password         = ""
  val hdfsRoot         = s"$dir/user/hdfs"
  val mapRedHome       = s"${System.getProperty("user.home")}/.ivy2/cache"
  val importTableName  = s"customer_import_${UUID.randomUUID.toString.replace('-', '_')}"
  val dirStructure     = s"sales/books/$importTableName"
  val hdfsLandingPath  = s"$hdfsRoot/source/$dirStructure"
  val hdfsArchivePath  = s"$hdfsRoot/archive/$dirStructure"
  val timePath         = List("2014", "10", "10") mkString File.separator

  val options = SqoopImportConfig.options[ParlourImportDsl](
    connectionString, username, password, importTableName
  ).splitBy("id")

  Class.forName("org.hsqldb.jdbcDriver")

  SqoopExecutionTest.setupEnv()

  val compressedRecordReader =
    ThermometerRecordReader[String]((conf, path) => IO {
      val ctx = new Context(conf)
      val in = new GzipCompressorInputStream(
        ctx.withFileSystem[InputStream](_.open(path))("compressedRecordReader")
      )
      try Streams.read(in).lines.toList
      finally in.close
    })

  def endToEndImportWithSuccess = {
    val config = SqoopImportConfig(hdfsLandingPath, hdfsArchivePath, timePath, options)
    val (path, count) = executesSuccessfully(sqoopImport(config))
    facts(
      ImportPathFact(path),
      s"$hdfsLandingPath/$timePath" </> "part-m-00000" ==> lines(data),
      s"$hdfsArchivePath/$timePath" </> "part-00000.gz" ==> records(compressedRecordReader, data)
    )
    count must_== 3
  }

  def endToEndImportWithSqlQuery = {
    val optionsWithQuery = SqoopImportConfig.optionsWithQuery[ParlourImportDsl](connectionString, username, password,
      s"SELECT * FROM $importTableName WHERE $$CONDITIONS", Some("id"))

    val config = SqoopImportConfig(hdfsLandingPath, hdfsArchivePath, timePath, optionsWithQuery)
    val (path, count) = executesSuccessfully(sqoopImport(config))
    facts(
      ImportPathFact(path),
      s"$dir/user/hdfs/archive/sales/books" </> importTableName </> "2014/10/10" </> "part-00000.gz" ==>
        records(compressedRecordReader, data),
      s"$dir/user/hdfs/source/sales/books" </> importTableName </> "2014/10/10" </> "part-m-00000"   ==>
        lines(data)
    )
    count must_== 3
  }

  def endToEndImportWithException = {
    val config = SqoopImportConfig(hdfsLandingPath, hdfsArchivePath, timePath, options)
    execute(sqoopImport(config, "very_bad_col=1")) must beLike { case Failure(_) => ok }
  }

  def endToEndImportWithoutTable = {
    val config = SqoopImportConfig(
      hdfsLandingPath, hdfsArchivePath, timePath, options.tableName(null)
    )
    execute(sqoopImport(config)) must beLike { case Failure(_) => ok }
  }

  val teradataOptions = SqoopImportConfig.options[TeradataParlourImportDsl](
    connectionString, username, password, importTableName
  ).splitBy("id")
  
  def endToEndImportWithTeradataConnMan = {
    SqoopExecutionTest.setupEnv()
    val config = SqoopImportConfig(hdfsLandingPath, hdfsArchivePath, timePath, teradataOptions)
    execute(sqoopImport(config)) must beLike { case Failure(_) => ok }
  }

  def endToEndImportWithTeradataResetConnMan = {
    SqoopExecutionTest.setupEnv(customConnMan=Some(""))
    endToEndImportWithSuccess
  }

  def doubleImport = {
    implicit val session = AutoSession
    val data2 = List("1|1", "2|2", "3|3")

    SQL(s"""
      create table table2 (
        id integer,
        other integer
      )
    """).execute.apply()

    data2.map(line => line.split('|')).foreach(row => 
      SQL(s"""insert into table2(id, other) values ('${row(0)}', '${row(1)}')""").update().apply()
    )

    val dirStructure2    = s"sales/books/table2"
    val hdfsLandingPath2 = s"$hdfsRoot/source/$dirStructure2"
    val hdfsArchivePath2 = s"$hdfsRoot/archive/$dirStructure2"
    val options          = SqoopImportConfig.optionsWithQuery[ParlourImportDsl](connectionString, username, password,
      s"SELECT * FROM $importTableName WHERE $$CONDITIONS", Some("id"))
    val options2         = SqoopImportConfig.optionsWithQuery[ParlourImportDsl](connectionString, username, password,
      s"SELECT * FROM table2 WHERE $$CONDITIONS", Some("id"))
    val config           = SqoopImportConfig(hdfsLandingPath, hdfsArchivePath, timePath, options)
    val config2          = SqoopImportConfig(hdfsLandingPath2, hdfsArchivePath2, timePath, options2)

    val execution = for {
      (_, c1) <- sqoopImport(config)
      (_, c2) <- sqoopImport(config2)
    } yield (c1, c2)

    executesSuccessfully(execution) must_==((3, 3))
    facts(
      s"$hdfsLandingPath/$timePath" </> "part-m-00000" ==> lines(data),
      s"$hdfsLandingPath2/$timePath" </> "part-m-00000" ==> lines(data2)
    )
  }

  val data = List("1|Fred|001|D|M|259", "2|Betty|005|D|M|205", "3|Bart|002|F|M|225")

  def tableSetup(connectionString: String, username: String, password: String, table: String = "customer_import"): Unit = {
    ConnectionPool.singleton(connectionString, username, password)
    implicit val session = AutoSession

    SQL(s"""
      create table $table (
        id integer,
        name varchar(20),
        accr varchar(20),
        cat varchar(20),
        sub_cat varchar(20),
        balance integer
      )
    """).execute.apply()

    data.map(line => line.split('|')).foreach(row => 
      SQL(s"""insert into ${table}(id, name, accr, cat, sub_cat, balance)
        values ('${row(0)}', '${row(1)}', '${row(2)}', '${row(3)}', '${row(4)}', '${row(5)}')""").update().apply()
    )
  }

  override def before: Any = tableSetup(
    connectionString, username, password, importTableName
  )

  object ImportPathFact {
    def apply(actualPath: String) = Fact(_ => actualPath must beEqualTo(s"$hdfsLandingPath/$timePath"))
  }
}
