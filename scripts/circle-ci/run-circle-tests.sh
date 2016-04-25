#!/usr/bin/env bash

CASSANDRA=':atlasdb-cassandra-tests:check'
SHARED=':atlasdb-tests-shared:check'

case $CIRCLE_NODE_INDEX in
    0) ./gradlew --parallel --continue check -x $CASSANDRA -x $SHARED ;;
    1) ./gradlew --parallel $CASSANDRA ;;
    2) ./gradlew --parallel $SHARED ;;
esac
