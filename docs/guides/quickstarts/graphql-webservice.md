---
id: graphql-webservice
title: "ZIO Quickstart: Building GraphQL Web Service"
sidebar_label: "GraphQL Web Service"
---

This quickstart shows how to build a GraphQL web service using ZIO. It uses

- [ZIO HTTP](https://zio.dev/zio-http/) for the HTTP server
- [Caliban](https://ghostdogpr.github.io/caliban/) for the GraphQL

Creating GraphQL API using Caliban is an easy process. We can define our data models using case classes and ADTs. Then Caliban can derive the whole GraphQL schema from these data models.

## Running The Example

First, open the console and clone the [ZIO Quickstarts](https://github.com/zio/zio-quickstarts) project using `git` (or you can simply download the project) and then change the directory:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-graphql-webservice
```

Once you are inside the project directory, run the application:

```bash
sbt run
```

Alternatively, to enable hot-reloading and prevent port binding issues, you can use:

```bash
sbt reStart
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Testing The Quickstart

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
