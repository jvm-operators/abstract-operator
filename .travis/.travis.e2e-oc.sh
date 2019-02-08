#!/bin/bash

DIR="${DIR:-$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )}"
BIN="oc"
CRD="1"
MANIFEST_SUFIX=""
KIND="SparkCluster"
VERSION="v3.9.0"

RUN=0 source "${DIR}/../spark-operator/.travis/.travis.prepare.openshift.sh"
source "${DIR}/../spark-operator/.travis/.travis.test-common.sh"

run_tests() {
  testCreateCluster1 || errorLogs
  testScaleCluster || errorLogs
  testDeleteCluster || errorLogs
  sleep 5

  testMetricServer || errorLogs
  logs
}

main() {
  [ "$TRAVIS" == "true" ] && download_openshift
  [ "$TRAVIS" == "true" ] && setup_insecure_registry
  setup_manifest

  DIR="${DIR}/../spark-operator/.travis"

  export total=5
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