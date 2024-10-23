# Managing Philter’s Configuration in an Auto-Scaling Environment

This article describes how Philter's configuration can be managed when Philter is deployed in an auto-scaling environment.

## Updating Philter Configuration Values

Philter reads its settings from the `philter.properties` file when Philter starts. This file must reside alongside Philter wherever Philter is deployed. When Philter is deployed in an auto-scaling environment, updating a configuration requires updating the configuration value on all instances of Philter. There are a few approaches that can be taken.

### Deployment via a Custom Machine Image

One way to update the configuration values is to use a custom machine ("pre-baked") image of Philter. When a configuration needs changed, change the configuration value in the machine image and update the auto-scaling environment with the latest machine image. Now, begin substituting the currently running Philter instances with new instances from the updated machine image.

### Updating Configuration using an External File

In this method, a copy of Philter's application.properties file is stored on a remote file system, such as Amazon S3. A cron job runs on each deployed Philter instance to periodically download the application.properties file, copy it to the appropriate location, and then restart the Philter service. This method allows you to modify the configuration on Philter on all of the instances with less moving parts than the previous option.

The following is an example bash script that uses the AWS CLI to copy the `philter.properties` file and restart Philter.

```
#!/bin/bash
aws s3 cp s3://your-bucket/application.properties /opt/philter/application.properties
sudo systemctl restart philter.service
sudo systemctl restart philter-ner.service
```
