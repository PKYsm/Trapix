#!/bin/bash
# Run this script once after cloning to download gradle-wrapper.jar
# OR let GitHub Actions handle it automatically

echo "Downloading gradle-wrapper.jar..."
mkdir -p gradle/wrapper

GRADLE_VERSION="8.6"
JAR_URL="https://github.com/gradle/gradle/raw/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar"

# Try curl
curl -L -o gradle/wrapper/gradle-wrapper.jar "$JAR_URL" 2>/dev/null || \
# Try wget  
wget -O gradle/wrapper/gradle-wrapper.jar "$JAR_URL" 2>/dev/null || \
echo "Please download gradle-wrapper.jar manually from: https://services.gradle.org/distributions/"

echo "Done!"
