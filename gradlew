#!/bin/sh
# Smart gradlew - works without gradle-wrapper.jar
# If wrapper jar exists, use it. Otherwise use system/downloaded gradle.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties"

# Try wrapper jar first
if [ -f "$WRAPPER_JAR" ] && [ "$(wc -c < "$WRAPPER_JAR")" -gt 50000 ]; then
  exec java -jar "$WRAPPER_JAR" "$@"
fi

# Find gradle in PATH or common locations
GRADLE_CMD=""
if command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD="gradle"
elif [ -d "$HOME/gradle-installations/installs" ]; then
  GRADLE_CMD=$(find "$HOME/gradle-installations/installs" -name "gradle" -type f | head -1)
elif [ -d "/opt/gradle" ]; then
  GRADLE_CMD=$(find "/opt/gradle" -name "gradle" -type f | head -1)
fi

if [ -n "$GRADLE_CMD" ]; then
  exec "$GRADLE_CMD" "$@"
fi

# Last resort: download gradle and run
GRADLE_VERSION="8.6"
GRADLE_ZIP="$SCRIPT_DIR/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_DIR="$SCRIPT_DIR/gradle-${GRADLE_VERSION}"

if [ ! -d "$GRADLE_DIR" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -L -o "$GRADLE_ZIP" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q "$GRADLE_ZIP" -d "$SCRIPT_DIR"
fi

exec "$SCRIPT_DIR/gradle-${GRADLE_VERSION}/bin/gradle" "$@"
