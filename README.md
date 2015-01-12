maestro
=======

[![Stories in Ready](https://badge.waffle.io/commbank/maestro.png?label=ready&title=Ready)](https://waffle.io/commbank/maestro) 
[![Build Status](https://travis-ci.org/CommBank/maestro.svg?branch=master)](https://travis-ci.org/CommBank/maestro)
[![Gitter chat](https://badges.gitter.im/CommBank/maestro.png)](https://gitter.im/CommBank/maestro)


```
maestro: a distinguished conductor
```

The `maestro` library is responsible for providing convenient APIs for marshalling and
orchestrating data around for etl type work.

The primary goal of `maestro` is to provide the ability to make it _easy_ to manage
data sets with out sacrificing safety or robustness. This is achieved via
strongly-typed schemas describing the fixed structure of data, and working on
APIs for manipulating those structures in a sensible way that scales to data sets
with 100s of columns.

[Scaladoc](https://commbank.github.io/maestro/latest/api/index.html)

Documentation
-------------

* Github Pages: https://commbank.github.io/maestro/index.html
* Extended documentation: https://github.com/CommBank/maestro/tree/master/doc

starting point
--------------

`maestro` is designed to work with highly structured data. It is
expected that all data-sets manipulated by `maestro` at some level
(maybe input, output or intermediate representations) have a well
defined wide row schema and fixed set of columns.

At this point, `maestro` supports `thrift` for schema definitions.

`maestro` uses the thrift schema definition to derive as much meta-data and
implementation of custom processing (such as printing and parsing) as it
can. It then provides APIs that use these "data type" specific tools to
provide generic "tasks" like generating analytics views.


5 minute quick-start
--------------------

### Defining a thrift schema.

This is not the place for a full thrift tutorial, so I will skip a lot
of the details, but if you don't understand thrift or would like more
complete documentation then <http://diwakergupta.github.io/thrift-missing-guide/>
is a really good reference.

So if a dataset was going to land on the system, we would define a
schema accurately defining the columns and types:

```

#@namespace scala au.com.cba.omnia.etl.customer.thrift

struct Customer {
  1  : string CUSTOMER_ID
  2  : string CUSTOMER_NAME
  3  : string CUSTOMER_ACCT
  4  : string CUSTOMER_CAT
  5  : string CUSTOMER_SUB_CAT
  6  : i32 CUSTOMER_BALANCE
  7  : string EFFECTIVE_DATE
 }

```

This is a simplified example, a real data set may have 100s of
columns, but it should be enough to demonstrate. The important points
here are that _order_ is important, the struct should be defined to
have fields in the same order as input data, and _types_ are
important, they should accurately describe the data (and will be used
to infer how the data should be parsed and validated).


### Building an `Execution` pipeline from the built in tools


A pipeline is defined via a scalding `Execution`, generally involving
multiple steps, with each step potentially depending on the results of
previous steps.  This is often neatly expressed as a Scala
`for`-`yield` comprehension like in the example below.

The steps in a pipeline can include scalding jobs, hive queries,
sqoop import/export, hdfs operations, other maestro `Executions` and
various other ways of specifying and combining steps.

An example execution pipeline that loads data via hive is the 
[CustomerExecution](maestro-example/src/main/scala/au/com/cba/omnia/maestro/example/CustomerExecution.scala).
An extract follows to give the flavour of executions and the their configuration.

```scala
/** Configuration for a customer execution example */
case class CustomerConfig(config: Config)  {
  val maestro   = MaestroConfig(/*...*/)
  val upload    = maestro.upload()
  val load      = maestro.load[Customer](none="null")
  val dateTable = maestro.partitionedHiveTable[Customer, (String, String, String)] (/*...*/)
  val catTable  = maestro.partitionedHiveTable[Customer, String](/*...*/)
  val queries = List(
    s""""INSERT OVERWRITE TABLE ${catTable.name} PARTITION (partition_cat)
       | SELECT id, name, acct, cat, sub_cat, -10, effective_date, cat AS partition_cat
       | FROM ${dateTable.name}""".stripMargin,
    s""""SELECT COUNT(*) FROM ${catTable.name}""""
  )
}

/** Customer execution example */
object CustomerExecution {
  /** Create an example customer execution */
  def execute: Execution[(LoadSuccess, Long)] = for {
    conf           <- Execution.getConfig.map(CustomerConfig(_))
    uploadInfo     <- upload(conf.upload)
    sources        <- uploadInfo.withSources
    (pipe, ldInfo) <- load[Customer](conf.load, uploadInfo.files)
    loadSuccess    <- ldInfo.withSuccess
    (count1, _)    <- viewHive(conf.dateTable, pipe) zip viewHive(conf.catTable, pipe)
    _              <- Execution.fromHive(Hive.queries(conf.queries), /*...*/)
  } yield (loadSuccess, count1)
}
```
(This should be updated to be consistent with issue #295, including the yield.)


Concepts
--------

### Maestro

`Maestro` uses the metadata available from the thrift definition
to generate out significant supporting infrastructure customized for
_your_ specific record type. This gives us the ability to refer to fields
for partitioning, filtering and validation in a way that can be easily
be checked and validated up front (by the compiler in most circumstances,
and on start-up before things run in the worst case) and that those field
references can have large amounts of interesting metadata which allows
us to automatically parse, print, validate, filter, partition the data
in a way that we _know_ will work before we run the code (for a valid
schema).

### Tasks

Tasks are not a concrete concept, it is just a name used to indicate that a
function returns a scalding job for integration into a cascade. Tasks differ
from raw Jobs in that they rely on the metadata generated by `Maestro`
and can generally only by executed via their scala api and are not intended to
be standalone jobs that would be run by themselves.

### Partitioners

Partitioners are really simple. Partitioners are just a list of fields to
partition a data set by.

The primary api is the list of fields you want to partition on:

```scala
Partiton.byFields(Fields.CUSTOMER_CAT, Fields.CUSTOMER_SUB_CAT)
```

The api also has special support for dates of the `yyyy-MM-dd` form:

```scala
Partiton.byDate(Fields.EFFECTIVE_DATE)
```

This will use that field, but split the partitioning into 3 parts of
yyyy, MM and dd.


### Filters

Filters again are really simple (hopefully). By default filters
allow everything. A filter can then be refined via either
blacklists (i.e. exclude these columns) or whitelists (i.e.
only include these columns).

Examples:
```scala
 // everything except effective date
Filter.exclude(Fields.EFFECTIVE_DATE)

 // _only_ effective date and customer id
Filter.include(Fields.EFFECTIVE_DATE, Fields.CUSTOMER_ID)
```

### Validators

Validators start to get a bit more advanced, but hopefully not too bad.
A Validator can be thought of as something that is a function from the record
type to either an error message or an "ok" tick of approval. In a lot of
cases this understanding can be simplified to saying it is a combination
of a `Field` to validate and a `Check` to apply. There are a few builtin
checks provided, if you want to do custom checking you can fail back to
defining a custom function.

```scala
Validator.all(
  Validator.of(fields.EFFECTIVE_DATE, Check.isDate),
  Validator.of(fields.CUSTOMER_CAT, Check.oneOf("BANK", "INSURE")),
  Validator.of(fields.CUSTOMER_NAME, Check.nonempty),
  Validator.of(fields.CUSTOMER_ID, Check.matches("\d+")),
  Validator.by[Customer](_.customerAcct.length == 4, "Customer accounts should always be a length of 4")
)
```

### Type Mappings

| Thrift Type                                       | Hive Type                                                                                     | Scala Type    |
| ------------------------------------------------- |:---------------------------------------------------------------------------------------------:| -------------:|
| bool: A boolean value (true or false), one byte   | BOOLEAN                                                                                       | bool          |
| byte: A signed byte                               | TINYINT (1-byte signed integer, from -128 to 127)                                             | byte          |
| i16: A 16-bit signed integer                      | SMALLINT (2-byte signed integer, from -32,768 to 32,767)                                      | short         |
| i32: A 32-bit signed integer                      | INT (4-byte signed integer, from -2,147,483,648 to 2,147,483,647)                             | int           |
| i64: A 64-bit signed integer                      | BIGINT (8-byte signed integer, from -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807)  | BigInteger    |
| double: A 64-bit floating point number            | DOUBLE (8-byte double precision floating point number)                                        | double        |
| string: Encoding agnostic text or binary string   | string 

### Hive

Maestro allows you to write directly to Hive tables and to run queries on Hive tables as part of a
Maestro job.

Create a `HiveTable` to describe/reference a specific hive table. This is required by the other hive
related methods as identifier. The `source` and `sink` methods on the `HiveTable` provide Scalding
sources and sinks for typed pipes to read from or write to. `name` provides a fully qualifed name
that can be used inside hql.

`viewHive` allows the Maestro job to write out the data to a partitioned hive table in parquet
similar to `view`. However, it also creates the hive table if it doesn't already exist. Otherwise,
it just verifies the schema.

`hiveQuery` enables the running of a hive query as part of the Maestro job. Apart from the query
string it also expects a list of input hive tables and optionally an output table. This is required
for Maestro to properly schedule interdependent flows. It is solely use for scheduling and has no
effect on the query itself. In order to run a hive query the Maestro job needs to extend
`MaestroCascade`. See the example for more details.

### Limitations

Currently it is not possible for our implementation to read in data from the partition columns.
Instead it is expected that all the data is solely contained inside the core columns of the table
itself. It is, therefore, not possible to partition on the same column as a field of the thrift
struct (instead a duplicate column with a different name is required). Partition columns can only
be used for hive performance reasons and not to carry information.

In order for the job to work the hive-site.xml needs to be on the classpath when the job is
initiated and on every node.

### Known issues

* Writing out hive files currently only works if the metastore is specified as thrift endpoint
  instead of database.
  ```
    <property>
      <name>hive.metastore.uris</name>
      <value>thrift://metastore:9083</value>
    </property>
    ```
* In order to run queries the hive-site.xml need to include the `yarn.resourcemanager.address`
  property even if the value is bogus.
  ```
    <property>
      <name>yarn.resourcemanager.address</name>
      <value>bogus</value>
    </property>
  ```
* In order to run queries with partitioning the partition mode needs to be set to nonstrict.
  ```
    <property>
      <name>hive.exec.dynamic.partition.mode</name>
      <value>nonstrict</value>
    </property>
  ```

You can start with the [example hive-site.xml](doc/hive-site.xml).  To use this either
install it on your cluster, or
add it to your project's resources directory so that it is included in your jar.

### Advanced tips & tricks

The best tip for advanced pipelines is to look carefully at how
the maestro tasks are implemented. You have the same set of tools
available to you and can jump down to the same lower-level of
abstraction by just implementing a scalding job, and extending the
`Maestro` class.
