M ?= mvn

.PHONY: build
build:
	echo -e "travis_fold:start:jbuild\033[33;1mBuilding the Java code\033[0m"
	MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" $(M) clean package -DskipTests
	echo -e "\ntravis_fold:end:jbuild\r"

.PHONY: install-parent
install-parent:
	git clone --depth=1 --branch master https://github.com/jvm-operators/operator-parent-pom.git && cd operator-parent-pom && MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" $(M) clean install && cd - && rm -rf operator-parent-pom

.PHONY: travis-e2e-use-case
travis-e2e-use-case:
	./.travis/.travis.e2e-oc.sh

.PHONY: build-travis
build-travis: install-parent build

.PHONY: javadoc
javadoc:
	echo -e "travis_fold:start:javadoc\033[33;1mGenerating Javadoc\033[0m"
	$(M) javadoc:javadoc
	echo -e "\ntravis_fold:end:javadoc\r"

.PHONY: update-parent
update-parent:
	$(M) -U versions:update-parent
