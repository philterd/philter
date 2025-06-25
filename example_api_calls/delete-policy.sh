#!/bin/bash -e

POLICY_NAME="default"

curl -s -X DELETE "http://localhost:8080/api/policies/${POLICY_NAME}"
