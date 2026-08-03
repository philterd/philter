#!/bin/bash -e

POLICY_NAME="default"

curl -k -s -X GET "https://localhost:8080/api/health"
