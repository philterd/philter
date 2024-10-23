# Monitoring Philter in AWS

A deployment of Philter in AWS can be monitored by multiple methods. Here we'll discuss some of the options available when Philter is used in AWS.

### Monitoring Philter's Application Log with CloudWatch Logs

> Although no sensitive information is purposely logged to Philter's log files, it is possible for sensitive information to be inadvertently included through some events. For this reason, it is important to ensure that your location for storing Philter's logs are suitable for containing sensitive information such as PHI and PII.


Philter's application log is located at `/var/log/philter/philter.log`. When deploying multiple instances of Philter it is useful to have the log files centralized in a single location. We can do this using CloudWatch Logs.

The first thing to do is to ensure the Philter instance has an appropriate IAM role and policy. The policy must allow write access to CloudWatch Logs. The following policy is sufficient:

```
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
    ],
      "Resource": [
        "arn:aws:logs:*:*:*"
    ]
  }
 ]
}
```

Next, [install the CloudWatch Logs Agent](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/QuickStartEC2Instance.html) on the instance. Configure the agent to send Philter's log to CloudWatch Logs. Modify the CloudWatch Logs configuration file to include Philter's log file:

```
[/var/log/philter/philter.log]
file = /var/log/philter/philter.log
log_group_name = /var/log/philter/philter.log
log_stream_name = {instance_id}
datetime_format = %b %d %H:%M:%S
```

After restarting the agent, Philter's log file will be available in the CloudWatch Logs console.

### Monitoring Philter's Availability with an Elastic Load Balancer

Philter's REST API includes an endpoint that returns the status of Philter. When operating normally, the `/api/status` endpoint returns `HTTP 200 OK`. This endpoint is ideal for monitoring by a service such as an Elastic Load Balancer's health checks. The full endpoint URL will be similar to `https://instance:8080/api/status`.

Note that, by default, Philter uses a self-signed SSL certificate for its HTTPS interface. In some situations it may be necessary to replace this self-signed certificate with a certificate signed by a trusted authority.

### Monitoring Philter's Metrics with CloudWatch Metrics

Philter captures various metrics during its operation. These metrics are exposed via several interfaces. The metrics are exposed via JMX and can also be reported to CloudWatch Metrics as custom metrics. To enable metric reporting to CloudWatch set the appropriate configuration settings in Philter's properties. (Refer to Philter's user documentation for a description of the configuration properties.) Now restart Philter for the changes to take affect. Philter will now publish metrics to CloudWatch Metrics.

These metrics can be used to trigger alerts based on certain thresholds or be used to trigger auto-scaling if Philter is deployed in an auto-scaling group.
