# abstract-operator

[![Build status](https://travis-ci.org/jvm-operators/abstract-operator.svg?branch=master)](https://travis-ci.org/jvm-operators/abstract-operator)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

`ConfigMap`-based approach for lyfecycle management of various resources in Kubernetes and OpenShift.

## Example Implementations
* [spark-operator](https://github.com/radanalyticsio/spark-operator) - Java operator for managing Apache Spark clusters and apps
* [scala-example-operator](https://github.com/jvm-operators/scala-example-operator) - Minimalistic operator in Scala

## Code
This library can be simply used by adding it to classpath; creating a new class that extends `AbstractOperator`. This 'concrete operator' class needs to also have the `@Operator` annotation on it. For capturing the information about the monitored resources one has to also create a class that extends `EntityInfo` and have arbitrary fields on it with getters and setters.

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
