#!/bin/bash

# Don't trace since the URI contains a password that shouldn't show up in the logs
set -o errexit  # Exit the script with error if any of the commands fail

# Supported/used environment variables:
#       MONGODB_URI             Set the URI, including an optional username/password to use to connect to the server via MONGODB-AWS
#                               authentication mechanism
#       JDK                     Set the version of java to be used.  Java versions can be set from the java toolchain /opt/java
#                               "jdk5", "jdk6", "jdk7", "jdk8", "jdk9", "jdk11"

JDK=${JDK:-jdk11}
MONGODB_URI=${MONGODB_URI:-"mongodb://localhost"}

############################################
#            Main Program                  #
############################################

echo "Running MONGODB-AWS authentication tests"

export JAVA_HOME="/opt/java/${JDK}"

MONGODB_URI="${MONGODB_URI}/aws?authMechanism=MONGODB-AWS&authSource=\$external"
if [[ -n ${SESSION_TOKEN} ]]; then
    MONGODB_URI="${MONGODB_URI}&authMechanismProperties=AWS_SESSION_TOKEN:${SESSION_TOKEN}"
fi

echo "Running tests with ${JDK}"
./gradlew -version
./gradlew -PjdkHome=${JAVA_HOME} -Dorg.mongodb.test.uri=${MONGODB_URI} --stacktrace --debug --info driver-core:test --tests AwsAuthenticationSpecification
