SQL Explorer
============

Core Concepts
-------------

### Sorting

SQL Explorer sorts entities such as queries and tables based on relevancy, using a custom comparator for `SqlProfile`
objects. The comparator first orders by `QueryActivityStatus` (ACTIVE, DORMANT, UNKNOWN), which is determined in the
evidence input service based on the last seen time and execution count of a query. Within the same activity status,
profiles are sorted by the presence of runtime metrics, and then by total execution time (descending). This ensures that
recently and frequently used queries appear first, while dormant or less relevant queries are deprioritized. The
comparator logic is defined in `SqlProfileExtension.comparator` and is used throughout the application, including in the
evidence input service after merging and building profiles.

Tables, queries, tags, and other entities are ordered by how heavily they are used. Relevancy is calculated by the last
recorded query time and the total amount of time spent in queries. Queries that have not executed in 2 months are
considered dormant and are deprioritized in the sorting.

### Hierarchical URLs

The application uses hierarchical URLs to represent navigation state and context. For example, URLs encode the current
database, schema, table, or query being viewed. This enables deep linking, bookmarking, and browser navigation, and
supports a clear mapping between the UI state and the underlying data model.

**Examples:**
- `/api/sql-explorer/analyzed-sql/{id}` — Access a specific analyzed SQL profile by its unique ID.
- `/api/sql-explorer/tables/{schema}` — List all tables within a given schema.
- `/api/sql-explorer/table/{schema}/{name}` — Get details for a specific table in a schema.
- `/api/sql-explorer/table/{schema}/{name}/column/{column}` — Get queries for a specific column in a table.
- `/api/sql-explorer/graph/schema/{schema}` — Visualize table relationships for a schema.
- `/api/sql-explorer/tables/tag/{name}` — List tables associated with a specific tag.
- `/api/sql-explorer/analyzed-sql/tag/{name}` — List analyzed SQL queries associated with a tag.

Modules
-------------

### Model

The Model module defines the core data structures representing databases, schemas, tables, columns, and relationships.
It acts as the single source of truth for the application's state and provides interfaces for querying and updating this
state. 

### Schema Reflection

Schema Reflection is responsible for introspecting the connected SQL database and extracting metadata such as tables,
columns, indexes, foreign keys, and constraints. This module provides APIs to fetch and cache schema information,
enabling other modules to work with up-to-date structural data.

#### Query Parsing

Query Parsing is a submodule of Analysis that tokenizes and parses SQL statements. It builds abstract syntax trees
(ASTs) from query text, enabling deeper understanding of query structure, dependencies, and semantics. Parsing is robust
to partial SQL and normalized SQL that is common in SQL metrics gathering. Queries are also assigned a normalized hash
that is more forgiving than a full SQL hash, this is especially useful for grouping machine generated queries together 
where semantics of the query are the same but aliases are different.

### Analysis

The Analysis module executes analyzers to categorize SQL queries and track their database usage. It includes static 
analysis of query text, detection of anti-patterns, and extraction of referenced tables and columns. Analysis results 
are used to inform reports, surface patterns, and for graph construction. Analysis is robust to partial SQL and will 
often be able to extract tables from queries that have been truncated. Analysis failures are not fatal, they are 
accessible in reports.

### Tagging

The Tagging module is used to create ad-hoc labels and annotations for database objects. Tags can be applied to queries 
and tables and then used to create simplified reports and graphs showing relationships between tagged entities. Tagging
is based on the hierarchical URL structure so it is easily extensible to any entity type.

### Graph

The Graph module constructs and manages dependency graphs representing relationships between database objects. Nodes
represent entities such as tables or queries, and edges represent dependencies (e.g., foreign keys, joins). The graph is 
used for visualization of relationships, support queries for a relationship can be drilled down into for more 
information.

### Evidence

Evidence management tracks supporting information for analysis results, such as query metrics, or schema structure.

### SQL Profiles

Analysis, Evidence, Tagging and Graph output are combined in the SQL Profiles Resource, profiles provide aggregates over 
tables, schemas, columns, and queries.

### Database Connections

Explorer connects to three different data sources.

1. **SQL Explorer Database**: The application database that stores the application's metadata, including analyzed SQL profiles
   and user-defined tags.
2. **Metadata Database**: The SQL database being analyzed, which the schema that Explorer introspects.
3. **Performance Schema**: A data source to extract performance metrics and query execution statistics, this data source 
   can be the same as the metadata database or optional if the csv importer is used. 



Code Structure
-------------
Java packages should be organized into top-down relationships, where a parent depends on its children but not vice-versa.

Organization should follow the rule: "what changes together, stays together". This means that persistent entities and 
their services and repositories should be in the same package. 