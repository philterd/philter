#!/bin/bash -e

POLICY_NAME="default"

curl -s -X GET "http://localhost:8080/api/health"
