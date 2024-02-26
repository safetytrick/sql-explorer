

Sql Explorer has always been used to coordinate and prioritize projects. Extracting and analyzing the completeness of 
a data extraction and migration script should be within its wheelhouse. 

Treat the project as a simple tree traversal management problem.

The migration is the root of the tree and wants to know all the datasources involved in the migration:

For Each Datasource
####################

MySQL Migration:

Question asked:
  Which schemas contain data that needs to be migrated?
Resolution: 
  For each schema record a strategy for migrating data within the schema.

SchemaMigrationStrategy:
TreePath: /$datasource/$schema

archetype: {
  type:
  description:
}
----
type: NOOP
description: No data migration is required

---

TreePath: /$datasource/$schema/$table

type: TableMigrationStrategy

description: Question asked: Which tables contain data that needs to be migrated?
Resolution: For each table record a strategy for migrating data within the schema.

---------

type: RootTableQueryingStrategy
description: Given the configuration from the root which rows should be copied?

TreePath: /$datasource/$schema/$table/$column

...

recurse down to columns

The Column producer may produce tree events if anyone has subscribed to the path  

