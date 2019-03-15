# abstract-operator

[![Build status](https://travis-ci.org/jvm-operators/abstract-operator.svg?branch=master)](https://travis-ci.org/jvm-operators/abstract-operator)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

`{CRD|ConfigMap}`-based approach for lyfecycle management of various resources in Kubernetes and OpenShift. Using the Operator pattern, you can leverage the Kubernetes control loop and react on various events in the cluster. The idea of the operator patern is to encapsulate the operational knowledge into the abovementioned control loop and declarative approach.

## Example Implementations
* [spark-operator](https://github.com/radanalyticsio/spark-operator) - Java operator for managing Apache Spark clusters and apps
* [java-example-operator](https://github.com/jvm-operators/java-example-operator) - Minimalistic operator in Java
* [scala-example-operator](https://github.com/jvm-operators/scala-example-operator) - Minimalistic operator in Scala
* [kotlin-example-operator](https://github.com/jvm-operators/kotlin-example-operator) - Minimalistic operator in Kotlin
* [groovy-example-operator](https://github.com/jvm-operators/groovy-example-operator) - Minimalistic operator in Groovy
* [haskell-example-operator](https://github.com/jvm-operators/haskell-example-operator) - Minimalistic operator in Haskell (Frege) and Groovy
* [javascript-example-operator](https://github.com/jvm-operators/javascript-example-operator) - Minimalistic operator in EcmaScript

## Code
This library can be simply used by adding it to classpath; creating a new class that extends `AbstractOperator`. This 'concrete operator' class needs to also have the `@Operator` annotation on it. For capturing the information about the monitored resources one has to also create a class that extends `EntityInfo` and have arbitrary fields on it with getters and setters.

This class can be also generated from the JSON schema. To do that add [jsonschema2pojo](https://github.com/radanalyticsio/spark-operator/blob/4f72e740ea2126843b0c240bd800a74169d5f1c2/pom.xml#L50:L53) plugin to the pom.xml and json schema to resources ([example](https://github.com/radanalyticsio/spark-operator/tree/4f72e740ea2126843b0c240bd800a74169d5f1c2/src/main/resources/schema)).

This is a no-op operator in Scala that simply logs into console when config map with label `radanalytics.io/kind = foo` is created.

```Scala
@Operator(forKind = "foo", prefix = "radanalytics.io", infoClass = classOf[FooInfo])
class FooOperator extends AbstractOperator[FooInfo] {
  val log: Logger = LoggerFactory.getLogger(classOf[FooInfo].getName)

  @Override
  def onAdd(foo: FooInfo) = {
    log.info(s"created foo with name ${foo.name} and someParameter = ${foo.someParameter}")
  }

  @Override
  def onDelete(foo: FooInfo) = {
    log.info(s"deleted foo with name ${foo.name} and someParameter = ${foo.someParameter}")
  }
}
```

### CRDs
By default the operator is based on `CustomResources`, if you want to create `ConfigMap`-based operator, add `crd=false` parameter in the `@Operator` annotation. The CRD mode will try to create the custom resource definition from the `infoClass` if it's not already there and then it listens on the newly created, deleted or modified custom resources (CR) of the given type.

For the CRDs the:
* `forKind` field represent the name of the `CRD` ('s' at the end for plural)
* `prefix` field in the annotation represents the group
* `shortNames` field is an array of strings representing the shortened name of the resource.
* `pluralName` field is a string value representing the plural name for the resource.
* `enabled` field is a boolean value (default is `true`), if disabled the operator is silenced
* as for the version, currently the `v1` is created automatically, but one can also create the `CRD` on his own before running the operator and providing the `forKind` and `prefix` matches, operator will use the existing `CRD`

#### Configuration
You can configure the operator using some environmental variables. Here is the list:
* `WATCH_NAMESPACE`, example values `myproject`, `foo,bar,baz`, `*` - what namespaces the operator should be watching for the events,
default: same namespace where the operator is deployed
* `CRD`, values `true/false` - config maps = `false`, custom resources = `true`, default: `true`
* `COLORS`, values `true/false` - if `true` there will be colors used in the log file, default: `true`
* `METRICS`, values `true/false` - whether start the simple http server that exposes internal metrics. These metrics are in the Prometheus compliant format and can be scraped by Prometheus; default: `false`
* `METRICS_JVM`, values `true/false` - whether expose also internal JVM metrics such as heap usage, number of threads and similar; default: `false`
* `METRICS_PORT`, example values `1337`; default: `8080`


## Documentation
[javadoc](https://jvm-operators.github.io/abstract-operator/)
