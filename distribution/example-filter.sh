#!/bin/bash
curl "http://localhost:8080/api/filter" \
	--data "George Washington was president and his ssn was 123-45-6789 and he lived in 90210 with diabetes and high blood pressure." -H "Content-type: text/plain"
