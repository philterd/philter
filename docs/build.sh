#!/bin/bash -e

# Builds the documentation and copies it into the app's src/main/resources/ directory.

mkdocs build

rm ../src/main/resources/META-INF/resources/public/docs/ -Rfv
mkdir ../src/main/resources/META-INF/resources/public/docs/
cp ./site/* ../src/main/resources/META-INF/resources/public/docs/ -Rv