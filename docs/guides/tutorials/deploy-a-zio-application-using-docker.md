---
id: deploy-a-zio-application-using-docker
title: "Tutorial: How to Deploy a ZIO Application Using Docker?"
sidebar_label: "Deploying a ZIO Application Using Docker"
---

## Introduction

Docker is a tool that allows us to package, ship, and run our applications in an isolated environment called a container. Using Docker, we can simplify the deployment process by isolating our applications in their own container and abstracting them from the host environment.

In this tutorial, we are going to learn how to build a Docker image for our ZIO application and then how to deploy it. Instead of writing the `Dockerfile` from scratch, we will use the _[sbt-native-packager](https://github.com/sbt/sbt-native-packager)_ to build our Docker image.

## Running The Examples

In [this quickstart](../quickstarts/restful-webservice.md), we developed a web service containing 4 different HTTP Applications. Now in this article, we want to dockerize this web application.

To access the code examples, you can clone the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-restful-webservice-dockerize
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

## Prerequisites

Before we can dockerize our web service, we need to [download and install Docker](https://docs.docker.com/get-docker/). So we assume that the reader has already installed Docker.

## Adding SBT Native Packager Plugin

The sbt-native-packager is an sbt plugin that enables us an easy way to package the application as a docker image and deploy that as a docker container.

First, we need to add the plugin to our `project/plugins.sbt` file:

```
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.9")
```

Now it's time to enable the `JavaAppPackaging` and `DockerPlugin` plugins. So we need to add the following lines in the `build.sbt` file:

```
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
```

## Building The Docker Image

The `DockerPlugin` plugin of _sbt-native-packager_ is responsible for configuring and building the docker image. We can run the following command to build the docker image:

```bash
$ sbt docker:publishLocal
```

After the docker image is built, we can run the `docker images` command to see the list of images that are currently available in the local docker registry:

```bash
$ docker images
REPOSITORY                          TAG     IMAGE ID       CREATED        SIZE
zio-quickstart-restful-webservice   0.1.0   c9ae81ee8fa6   17 hours ago   558MB
```

Note that, to see the generated `Dockerfile` we can use the `docker:stage` command:

```bash
$ sbt docker:stage
```

The `Dockerfile` will be generated in the `target/docker/stage` directory.

## Deploying The Docker Image

Now we can create a new container from this image by using the `docker run` command:

```bash
$ docker run -p 80:800 zio-quickstart-restful-webservice:0.1.0
```

Using the `-p` flag, we can specify the port that the container will listen to. As the web service is running on port `8080`, we bind this port to the host port `80`. Therefore, we can access the web service from the host machine through the port `80`:

```bash
$ curl -i "http://localhost/greet?name=Jane&name=John"
HTTP/1.1 200 OK
content-type: text/plain
content-length: 20

Hello Jane and John!
```

## Configuring The Docker Image

By default, the `sbt-native-packager` plugin will build the docker image using some predefined settings. So without any configuration we can use the `sbt docker:publish` or `sbt docker:publishLocal` commands to build and publish the docker image to the remote or local docker registry.

However, it is possible to configure the docker image, and it has lots of options to configure. We can find the list of available options in the [sbt-native-packager documentation](https://www.scala-sbt.org/sbt-native-packager/formats/docker.html#configuration).

### Exposing Container Ports

For example, when we build a docker image, we can specify which ports the container will listen to, by using the `EXPOSE` instruction in the `Dockerfile`. In the similar way, we can expose the ports using _sbt-native-packager_, by using the `dockerExposedPorts` setting in the `build.sbt` file:

```scala
dockerExposedPorts := Seq(8080)
```

Now, when we build the docker image and create a container from it, the new container has the port `8080` exposed. So when we run the `docker ps` command, we can see that the new container has the port `8080` exposed under the `PORTS` column:

```bash
$ docker ps
CONTAINER ID   IMAGE                                     COMMAND                  CREATED         STATUS         PORTS      NAMES
29982b053379   zio-quickstart-restful-webservice:0.1.0   "/opt/docker/bin/zio…"   3 seconds ago   Up 2 seconds   8080/tcp   bold_liskov
```

### Publishing The Docker Image to a Remote Registry

In a CI/CD pipeline, we might want to publish the docker image to a remote registry other than the local registry. We can do this by configuring the `dockerUsername` and `dockerRepository` settings in the `build.sbt` file:

```scala
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
```

Now, we can use the following command to publish the docker image to the remote registry:

```bash
$ export DOCKER_USERNAME=<username>  // e.g: johndoe
$ export DOCKER_REGISTRY=<registry>  // e.g: docker.io
$ sbt -Ddocker.username=$NAMESPACE -Ddocker.registry=$DOCKER_REGISTRY docker:publish
```

## Conclusion

In this tutorial, we learned how to build a docker image using _sbt-native-packager_, and how to deploy the docker image to the local or remote Docker registry.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) on Github.
