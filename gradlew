#!/bin/sh
APP_BASE_NAME=`basename "$0"`
DIRNAME=`dirname "$0"`
[ -z "$DIRNAME" ] && DIRNAME=.
APP_HOME=`cd "$DIRNAME" >/dev/null && pwd`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVA_EXE="java"
if [ -n "$JAVA_HOME" ] ; then
    JAVA_EXE="$JAVA_HOME/bin/java"
fi
exec "$JAVA_EXE" -Xmx1024m -Dfile.encoding=UTF-8 -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
