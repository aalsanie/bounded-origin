#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "No SHA-256 utility found (sha256sum or shasum required)." >&2
        exit 1
    fi
}

if [ ! -f "$WRAPPER_JAR" ]; then
    tmp="$WRAPPER_JAR.tmp"
    rm -f "$tmp"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --proto '=https' --tlsv1.2 --output "$tmp" "$WRAPPER_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --https-only --output-document="$tmp" "$WRAPPER_URL"
    else
        echo "gradle-wrapper.jar is missing and neither curl nor wget is available." >&2
        exit 1
    fi
    actual=$(sha256_file "$tmp")
    if [ "$actual" != "$WRAPPER_SHA256" ]; then
        rm -f "$tmp"
        echo "Gradle wrapper JAR checksum mismatch." >&2
        exit 1
    fi
    mv "$tmp" "$WRAPPER_JAR"
fi

actual=$(sha256_file "$WRAPPER_JAR")
if [ "$actual" != "$WRAPPER_SHA256" ]; then
    echo "Gradle wrapper JAR checksum mismatch." >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" -Dfile.encoding=UTF-8 -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
