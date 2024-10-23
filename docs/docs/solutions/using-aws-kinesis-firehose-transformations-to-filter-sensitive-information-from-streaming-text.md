# Using AWS Kinesis Firehose Transformations to Filter Sensitive Information from Streaming Text

AWS Kinesis Firehose is a managed streaming service designed to take large amounts of data from one place to another. For example, you can take data from places such as CloudWatch, AWS IoT, and custom applications using the AWS SDK to places such as Amazon S3, Amazon Redshift, Amazon Elasticsearch, and others. In this post we will use Amazon S3 as the firehose's destination.

Sometimes you want to manipulate the data as it goes through the firehose. This example solution shows how [Philter](https://www.philterd.ai/philter/) can be used with AWS Kinesis Firehose and AWS Lambda to remove sensitive information, such as PII and PHI, from the text as it travels through the firehose.

### Prerequisites

You must have a running instance of [Philter](https://www.philterd.ai/philter/). If you don't already have a running instance of Philter you can launch one through the [AWS Marketplace](https://aws.amazon.com/marketplace/pp/B07YVB8FFT). It is not required that the instance of Philter be running in AWS, but it is required that the instance of Philter be accessible from your AWS Lambda function. Running Philter and your AWS Lambda function in your own VPC allows you to communicate locally with Philter from the function. Otherwise, Philter will need to be available over the public internet or accessible over a VPN connection. [See all Philter launch options.](https://www.philterd.ai/philter/availability/)

### Configuring the Firehose and the Lambda Function

There is no need to duplicate an excellent blog post on creating a [Firehose Data Transformation with AWS Lambda](https://aws.amazon.com/blogs/compute/amazon-kinesis-firehose-data-transformation-with-aws-lambda/) to establish the Firehose and Lambda function resources in AWS. So, refer to that blog post and substitute the Python 3 code below.

To start, create an AWS Firehose and configure an AWS Lambda transformation. When creating the AWS Lambda function, select Python 3.7 and use the following code to submit text to Philter's [API](filtering-api.md).

```
from botocore.vendored import requests
import base64

def handler(event, context):

    output = []

    for record in event['records']:
        payload=base64.b64decode(record["data"])
        headers = {'Content-type': 'text/plain'}
        r = requests.post("https://PHILTER_IP:8080/api/filter", verify=False, data=payload, headers=headers, timeout=20)
        filtered = r.text
        output_record = {
            'recordId': record['recordId'],
            'result': 'Ok',
            'data': base64.b64encode(filtered.encode('utf-8') + b'\n').decode('utf-8')
        }
        output.append(output_record)

    return output
```

The following Kinesis Firehose test event can be used to test the function:

```
{
  "invocationId": "invocationIdExample",
  "deliveryStreamArn": "arn:aws:kinesis:EXAMPLE",
  "region": "us-east-1",
  "records": [
    {
      "recordId": "49546986683135544286507457936321625675700192471156785154",
      "approximateArrivalTimestamp": 1495072949453,
      "data": "R2VvcmdlIFdhc2hpbmd0b24gd2FzIHByZXNpZGVudCBhbmQgaGlzIHNzbiB3YXMgMTIzLTQ1LTY3ODkgYW5kIGhlIGxpdmVkIGF0IDkwMjEwLiBQYXRpZW50IGlkIDAwMDc2YSBhbmQgOTM4MjFhLiBIZSBpcyBvbiBiaW90aW4uIERpYWdub3NlZCB3aXRoIEEwMTAwLg=="
    },
    {
      "recordId": "49546986683135544286507457936321625675700192471156785154",
      "approximateArrivalTimestamp": 1495072949453,
      "data": "R2VvcmdlIFdhc2hpbmd0b24gd2FzIHByZXNpZGVudCBhbmQgaGlzIHNzbiB3YXMgMTIzLTQ1LTY3ODkgYW5kIGhlIGxpdmVkIGF0IDkwMjEwLiBQYXRpZW50IGlkIDAwMDc2YSBhbmQgOTM4MjFhLiBIZSBpcyBvbiBiaW90aW4uIERpYWdub3NlZCB3aXRoIEEwMTAwLg=="
    }    
  ]
}
```

This test event contains 2 messages and the data for each is base 64 encoded, which is the value "He lived in 90210 and his SSN was 123-45-6789." When the test is executed the response will be:

```
[
  "He lived in {{{REDACTED-zip-code}}} and his SSN was {{{REDACTED-ssn}}}.",
  "He lived in {{{REDACTED-zip-code}}} and his SSN was {{{REDACTED-ssn}}}."
]
```

When executing the test, the AWS Lambda function will extract the data from the requests in the firehose and submit each to Philter for filtering. The responses from each request will be returned from the function as a JSON list.

Note that in our Python function we are ignoring Philter's self-signed certificate. You should use a valid signed certificate for Philter and never disable certificate validation on clients.

When data is now published to the Kinesis Firehose stream, the data will be processed by the AWS Lambda function and Philter prior to exiting the firehose at its configured destination.

### Processing Data

We can use the AWS CLI to publish data to our Kinesis Firehose stream called `sensitive-text`:

```
aws firehose put-record --delivery-stream-name sensitive-text --record "He lived in 90210 and his SSN was 123-45-6789."
```

Check the destination Amazon S3 bucket and you will have a single object with the following line:

```
He lived in {{{REDACTED-zip-code}}} and his SSN was {{{REDACTED-ssn}}}.
```

You're now ready to pump data through the firehose.

### Conclusion

In this blog post we have created an AWS Firehose pipeline that uses an AWS Lambda function to remove sensitive information from the text in the streaming pipeline.

### Resources

* [Amazon Kinesis Data Firehose](https://aws.amazon.com/kinesis/data-firehose/)
* [Amazon Kinesis Data Firehose Data Transformation](https://docs.aws.amazon.com/firehose/latest/dev/data-transformation.html)
