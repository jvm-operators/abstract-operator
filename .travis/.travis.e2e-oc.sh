#!/bin/bash

DIR="${DIR:-$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )}"
BIN="oc"
CRD="1"
MANIFEST_SUFIX=""
KIND="SparkCluster"
VERSION="v3.9.0"

#RUN=0 source "${DIR}/../spark-operator/.travis/.travis.prepare.openshift.sh"
source "${DIR}/../spark-operator/.travis/.travis.test-common.sh"

run_tests() {
  testCreateCluster1 || errorLogs
  testScaleCluster || errorLogs
  testDeleteCluster || errorLogs
  sleep 5

  testMetricServer || errorLogs
  logs
}

prepare_operator() {
    set -ex
    LIBRARY_VERSION="$(mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version|grep -Ev '(^\[|Download\w+:)')"
    rm -rf ${DIR}/../spark-operator || true
    pushd ${DIR}/..
	git clone --depth=100 --branch master --recurse-submodules https://github.com/radanalyticsio/spark-operator.git
    cd spark-operator

    # checkout the latest release
    GIT_TAG="$(git describe --abbrev=0 --tags)"
	[[ "${GIT_TAG}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && git checkout "${GIT_TAG}"

    # use the -SNAPSHOTed version of abstract operator
	sed -i'' "s;\(<abstract-operator.version>\)\([^<]\+\);\1${LIBRARY_VERSION};g" pom.xml
    make build
    popd
    set +ex
}

main() {
  prepare_operator

  [ "$TRAVIS" == "true" ] && download_openshift
  [ "$TRAVIS" == "true" ] && setup_insecure_registry
  setup_manifest

  DIR="${DIR}/../spark-operator/.travis"

  export total=6
  export testIndex=0
  tear_down
  setup_testing_framework
  os::test::junit::declare_suite_start "operator/tests"
  cluster_up
  testCreateOperator || { oc get events; oc get pods; exit 1; }
  export operator_pod=`oc get pod -l app.kubernetes.io/name=spark-operator -o='jsonpath="{.items[0].metadata.name}"' | sed 's/"//g'`
  run_tests
  os::test::junit::declare_suite_end
  tear_down
}

main $@