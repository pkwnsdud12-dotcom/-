#!/bin/sh
# Gradle wrapper 실행 스크립트 (표준). gradle-wrapper.jar 필요.
DIR=$(cd "$(dirname "$0")" && pwd)
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
