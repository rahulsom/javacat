#!/bin/bash

set -euo pipefail

VERSION=${1:-3.13}
./gradlew :javacat-rest-ghes-${VERSION}:build -x :javacat-rest-ghes-${VERSION}:downloadSchema
ag TODO javacat-rest-ghes-${VERSION}/build --color \
    | sed -E "s/^.+TODO/TODO/g" \
    | sort \
    | uniq -c \
    | sort -n -k1