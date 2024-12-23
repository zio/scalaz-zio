---
id: build-a-graphql-webservice
title: "Tutorial: How to Build a GraphQL Web Service"
sidebar_label: "Building a GraphQL Web Service"
---

Having GraphQL APIs enables the clients the ability to query the exact data they need. This powerful feature makes GraphQL more flexible than RESTful APIs.

Instead of having endpoints for our resources, the GraphQL API only provides a set of types and fields in terms of schemas. The client can ask for this schema, and that will help the client to know what kind of data they can expect from the server, and finally, the client can use the schema to build its queries.

GraphQL supports three types of operations: **queries**, **mutations**, and **subscriptions**. In this tutorial, we are going to learn the foundation of GraphQL using _queries_. Once we learned that, we can easily write two other types of operations.

The corresponding source code for this tutorial is available on [GitHub](https://github.com/zio/zio-quickstarts). If you haven't read the [ZIO Quickstart: Building GraphQL Web Service](../quickstarts/graphql-webservice.md) yet, we recommend you to read it first and download and run the source code, before reading this tutorial.

## Installation

In this tutorial, we will build a GraphQL API using _Caliban_, and in order to serve it, we will use the _ZIO HTTP_ library. So let's install the necessary dependencies by putting the following lines in the _build.sbt_ file:

```sbt
libraryDependencies ++= Seq(
  "dev.zio"               %% "zio"              % "2.1.7",
  "com.github.ghostdogpr" %% "caliban-quick"    % "2.8.1"
)
```

Now, we are ready to jump into the next section.

## Creating a GraphQL Schema

When working with GraphQL, we need to define a _schema_, which defines what kind of data with which types we can query. In GraphQL, schemas are defined in their type system. For example, assume we have a data type called _Employee_, which had defined in scala as the following:

```scala mdoc:silent
case class Employee(name: String, role: Role)

sealed trait Role
object Role {
  case object SoftwareDeveloper       extends Role
  case object SiteReliabilityEngineer extends Role
  case object DevOps                  extends Role
}
```

It has two fields, _name_, and _role_, and the _role_ field is a _sealed trait_, which means that it can only be one of the three values, _SoftwareDeveloper_, _SiteReliabilityEngineer_, or _DevOps_.

If we want to define such a data type in GraphQL, we have something like this:

```graphql
type Employee {
  name: String!
  role: Role!
}

enum Role {
  SoftwareDeveloper
  SiteReliabilityEngineer
  DevOps
}
```

After defining the schema for our data types, the next step is to define queries that can be performed on the data. For example, if we want to query all the employees with a specific role, we can do that like this:

```graphql
type Queries {
  employees(role: Role!): [Employee!]!
}
```

Similarly, if we want to query an employee by its name, we can define that like this:

```graphql
type Queries {
  employee(name: String!): Employee
}
```

Fortunately, we are not required to define the schema in GraphQL manually. Instead, we can use the _Caliban_ library which has a built-in facility to generate the schema from our data types defined in Scala:

```scala mdoc:silent
case class EmployeesArgs(role: Role)
case class EmployeeArgs(name: String)

case class Queries(
    employees: EmployeesArgs => List[Employee],
    employee: EmployeeArgs => Option[Employee]
)
```

## Running the GraphQL Server

After defining all the queries, in order to serve the GraphQL API, we need to perform the following steps:

1. Create a `GraphQLInterpreter` instance, which is a wrapper around the _GraphQL API_. It allows us to add some middleware around the query execution.
2. Create an `HttpApp` instance from the `GraphQLInterpreter` instance. We can do this by using the `ZHttpAdapter.makeHttpService` defined in the `caliban-zio-http` module.
3. Serve the resulting `HttpApp` instance using the `Server.start` method of the _ZIO HTTP_ module.

```scala
import caliban._
import caliban.quick._

object MainApp extends zio.ZIOAppDefault {
  import caliban.schema.ArgBuilder.auto._
  import caliban.schema.Schema.auto._

  private val employees = List(
    Employee("Alex", Role.DevOps),
    Employee("Maria", Role.SoftwareDeveloper),
    Employee("James", Role.SiteReliabilityEngineer),
    Employee("Peter", Role.SoftwareDeveloper),
    Employee("Julia", Role.SiteReliabilityEngineer),
    Employee("Roberta", Role.DevOps)
  )

  override def run =
    graphQL(
      RootResolver(
        Queries(
          args => employees.filter(e => args.role == e.role),
          args => employees.find(e => e.name == args.name)
        )
      )
    ).runServer(
      port = 8088,
      apiPath = "/api/graphql",
      graphiqlPath = Some("/api/graphiql"),
    )

}
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Effectful Queries

In the previous section, we used an in-memory data structure to store the data. But, in real-world applications we usually want to perform some kind of effectful queries to retrieve the data from the database. In such cases, we can use queries that return `ZIO` values:

```diff
case class Queries(
-    employees: EmployeesArgs => List[Employee],
+    employees: EmployeesArgs => ZIO[UserRepo, Throwable, List[Employee]],
-    employee: EmployeeArgs => Option[Employee]
_    employee: EmployeeArgs => ZIO[UserRepo, Throwable, Option[Employee]]
)
```

As we see, each query is a function that takes some arguments and returns a `ZIO` workflow.

## Running the GraphQL Client

In this project, we have defined models of our employees with their names and roles. Then using GraphQL annotations, we asked Caliban to derive the GraphQL schema from these models.

So we can query all the employees that are software developers using the GraphQL query:

```graphql
query {
  employees(role: SoftwareDeveloper) {
    name
    role
  }
}
```

To run this query, we can use any of the GraphGL clients or use the following curl command:

```bash
curl 'http://localhost:8088/api/graphql' --data-binary '{"query":"query{\n employees(role: SoftwareDeveloper){\n name\n role\n}\n}"}'
```

The response will be as follows:

```json
{
  "data": {
    "employees": [
      {
        "name": "Maria",
        "role": "SoftwareDeveloper"
      },
      {
        "name": "Peter",
        "role": "SoftwareDeveloper"
      }
    ]
  }
}
```

## Conclusion

In this tutorial, we learned the basic elements of writing GraphQL web services, using the _Caliban_ library. Caliban has great documentation, which can be found [here](https://ghostdogpr.github.io/caliban/). We can learn more about this project by visiting its website.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) project.
