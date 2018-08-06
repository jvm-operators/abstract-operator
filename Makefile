.PHONY: build
build: 
	MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" ./mvnw clean package -DskipTests

.PHONY: install-parent
install-parent:
	git clone --depth=1 --branch master https://github.com/jvm-operators/operator-parent-pom.git && cd operator-parent-pom && MAVEN_OPTS="-Djansi.passthrough=true -Dplexus.logger.type=ansi $(MAVEN_OPTS)" ./mvnw clean install && cd - && rm -rf operator-parent-pom

.PHONY: build-travis
build-travis: install-parent build

.PHONY: javadoc
javadoc:
	./mvnw javadoc:javadoc

.PHONY: update-parent
update-parent:
	./mvnw versions:update-parent
