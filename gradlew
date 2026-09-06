#!/usr/bin/env sh
# If gradle-wrapper.jar is present, use it. Otherwise fall back to a Gradle
# on PATH (GitHub Actions installs 8.9 via gradle/actions/setup-gradle).
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR" ]; then
  JAVACMD=java
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  exec "$JAVACMD" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "gradle-wrapper.jar is missing and no 'gradle' is on PATH."
echo "Open this project in Android Studio, or run: gradle wrapper --gradle-version 8.9"
exit 1
