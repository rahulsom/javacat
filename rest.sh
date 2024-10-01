#!/bin/bash

set -euo pipefail

VERSION=${1:-3.13}

ROOTPROJECT_NAME=pulpogato
./gradlew :${ROOTPROJECT_NAME}-rest-ghes-${VERSION}:build -x :${ROOTPROJECT_NAME}-rest-ghes-${VERSION}:downloadSchema
ag TODO ${ROOTPROJECT_NAME}-rest-ghes-${VERSION}/build --color \
    | sed -E "s/^.+TODO/TODO/g" \
    | sort \
    | uniq -c \
    | sort -n -k1