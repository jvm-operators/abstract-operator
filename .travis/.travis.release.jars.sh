#!/bin/bash

set -xe

REPO="${REPO:-jvm-operators/abstract-operator}"

[ "$TRAVIS_BRANCH" = "master" -a "$TRAVIS_PULL_REQUEST" = "false" ] && LATEST=1

main() {
  if [[ "$LATEST" = "1" ]]; then
    echo "Pushing the -SNAPSHOT artifact to sonatype maven repo."
    releaseSnapshot
    javadoc
  elif [[ "${TRAVIS_TAG}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Releasing the '${TRAVIS_TAG}' maven artifacts."
    release
    javadoc
  else
    echo "Not doing the Maven release, because the tag '${TRAVIS_TAG}' is not of form x.y.z"
    echo "and also it's not a build of the master branch"
  fi
}

releaseSnapshot() {
    make build-travis && mvn -s ./.travis/settings.xml clean deploy
}

release() {
    openssl aes-256-cbc -K ${encrypted_ea794cf5410d_key} -iv ${encrypted_ea794cf5410d_iv} -in ./.travis/.signing.asc.enc -out ./signing.asc -d
    gpg --fast-import ./signing.asc &> /dev/null
    mvn -s ./.travis/settings.xml clean deploy -DskipLocalStaging=true -P release
    sleep 10
    local _repo_ids=`mvn -s ./.travis/settings.xml nexus-staging:rc-list | grep "ioradanalytics".*OPEN | cut -d' ' -f2 | tail -2 | sort -r`
    for _id in ${_repo_ids}; do
      mvn -s ./.travis/settings.xml nexus-staging:close nexus-staging:release -DstagingRepositoryId=${_id} || true
    done
    rm ./signing.asc
}

javadoc() {
    [ -z "$GH_TOKEN" ] && echo "GH_TOKEN not set, exiting.." && exit 0
    [[ "$LATEST" = "1" ]] && VERSION="latest" || VERSION=${TRAVIS_TAG}
    mvn -s ./.travis/settings.xml javadoc:javadoc
    cp -r ./target/site/apidocs/ /tmp/
    switchBranch
    rm -rf ./docs/${VERSION} || true
    mv /tmp/apidocs ./docs/${VERSION}

    # release
    if [[ "$LATEST" != "1" ]]; then
        ln -s ${VERSION} docs/latest-released
        echo "<li><b>${VERSION}</b> - <a href='/abstract-operator/docs/${VERSION}'>docs</a> - <a href='https://search.maven.org/artifact/io.radanalytics/abstract-operator/${VERSION}/jar'>maven</a></li>"  >> ./index.html
    fi
    pushToScm ${VERSION}
}

switchBranch() {
    git fetch origin +refs/heads/gh-pages:refs/remotes/origin/gh-pages
    git remote set-branches --add origin gh-pages
    git checkout --track -b gh-pages origin/gh-pages
}

pushToScm() {
    VERSION=$1
    git add -A
    git commit -m "Docs for version ${VERSION}."
    set +x
    git remote add ad-hoc-origin https://Jiri-Kremser:${GH_TOKEN}@github.com/${REPO}.git
    set -x
    git push ad-hoc-origin gh-pages
}

main
