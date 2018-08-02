.PHONY: build
build: 
	MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" ./mvnw clean package -DskipTests

.PHONY: install-parent
install-parent:
	rm -rf /tmp/operator-parent-pom && pushd /tmp && git clone https://github.com/jvm-operators/operator-parent-pom.git && cd operator-parent-pom && MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" ./mvnw clean install && popd && rm -rf /tmp/operator-parent-pom

.PHONY: build-travis
build-travis: install-parent build