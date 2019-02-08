#!/bin/bash

DIR="${DIR:-$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )}"
BIN="oc"
CRD="1"
MANIFEST_SUFIX=""
KIND="SparkCluster"
VERSION="v3.9.0"

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
    echo -e "travis_fold:start:prepareOp\033[33;1mPreparing operator\033[0m"
    #LIBRARY_VERSION="$(mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version|grep -Ev '(^\[|Download\w+:)')"
    LIBRARY_VERSION=$(cat pom.xml | grep -A 1 "<artifactId>abstract-operator</artifactId>" | grep version | sed 's;.*<version>\([^<]\+\).*;\1;g')
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
    source "./.travis/.travis.test-common.sh"
    echo "sourcing ./test/lib/init.sh"
    source "./test/lib/init.sh"
    echo -e "\ntravis_fold:end:prepareOp\r"
    source "./.travis/.travis.prepare.openshift.sh"
    popd
    
    set +ex
}

main() {
  set -x
  prepare_operator

  echo -e "travis_fold:start:e2e\033[33;1mSimple integration test\033[0m"
  DIR="${DIR}/../spark-operator/.travis"
  export total=6
  export testIndex=0
  tear_down
  os::util::environment::setup_time_vars
  os::test::junit::declare_suite_start "operator/tests"
  cluster_up
  testCreateOperator || { oc get events; oc get pods; exit 1; }
  export operator_pod=`oc get pod -l app.kubernetes.io/name=spark-operator -o='jsonpath="{.items[0].metadata.name}"' | sed 's/"//g'`
  run_tests
  os::test::junit::declare_suite_end
  tear_down
  echo -e "\ntravis_fold:end:e2e\r"
  set +x
}

main $@