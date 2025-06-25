#!/bin/bash -e

curl -s -X POST "http://localhost:8080/api/policies" \
  -H "Content-Type: application/json" \
  -d @../distribution/policies/default.json
