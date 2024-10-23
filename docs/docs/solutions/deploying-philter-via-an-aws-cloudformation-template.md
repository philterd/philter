# Deploying Philter in AWS via a CloudFormation Template

AWS CloudFormation can be used to automate the creation and tear down of your AWS cloud resources in a repeatable manner. [Philter](https://www.philterd.ai/philter/) can be included in your CloudFormation templates to also automate its deployment and configuration.

> This article is designed to be a "quick start" into CloudFormation and Philter. This article describes a CloudFormation template suitable for deploying Philter for purposes of integration testing. A template for deploying Philter for production use requires a few more changes.


### Finding Philter's AMI

To begin, you must have the AMI (e.g. `ami-123456789`) of Philter.

Alternatively, to find the AMI, launch Philter from the AWS Marketplace. If you have not already you will be prompted by the AWS Marketplace to subscribe to Philter. At the end of the subscription process you will be able to launch an instance into your AWS account. (You can select the smallest available instance size.) Do this and then navigate to your EC2 instances in the AWS Console.

In the EC2 Console locate the newly launched Philter instance. It will likely still be in a "Pending" state if not already completed launching. Click on the instance such that its details are displayed at the bottom of the EC2 Console. Locate the "AMI" property. This is the Philter AMI identifier. Make a note of this AMI or copy and paste it so you can reference it in your CloudFormation templates. You can now terminate the instance.

Note that when a new version of Philter is published to the AWS Marketplace it will have a different AMI identifier. If you want to use the newest version you will need to do the steps above again to find the new AMI identifier. See the Philter [AWS AMIs](aws-amis.md) for a sample script to automate finding the AMIs.
If you have difficulties finding the Philter AMI identifier please contact us for assistance.

### CloudFormation Template

You can use the AMI ID to launch one or more instances of Philter via your CloudFormation template. You can launch a single instance, multiple instances, or you can launch one or more instances as part of an autoscaling group. You have flexibility depending on your requirements for deploying Philter. In the example below we are going to launch a single instance of Philter.

We are going to base our template off the AWS sample for a single EC2 instance in a VPC. The sample template can be found [here](https://s3.amazonaws.com/cloudformation-templates-us-east-1/VPC_Single_Instance_In_Subnet.template). This template creates a new VPC along with the required subnet and route table.

* Open this template in your favorite text editor and locate the `AWSRegionArch2AMI` mapping. For your region, replace the value for the AMI with Philter's AMI you previously found.
* Next, locate the security group for the template that exposes port `80`. Replace `80` with `8080` to be able to access Philter's API. (You will also likely want to change the security groups' inbound CIDR from everyone (`0.0.0.0/0`) to a specific host or set of hosts within your network.
* Save and close the template.

Note that we only replaced the Philter AMI for your region in the template. The Philter AMI will be different for each AWS region.

### Launch the Stack

Now that we have the template we can create a stack from it. A stack is the set of resources that the template defines. You can think of a stack as being an instance of the template. We will use the AWS Console to create the stack. In the AWS Console navigate to the CloudFormation console. Locate the button to create a stack, walk through the steps uploading your template when prompted, and finish. Your new stack with Philter will now be launched. You can watch the stack's progress as CloudFormation creates its resources. When you are finished with the stack you can delete it and all resources that were created for the stack, such as the Philter instance, will be deleted.

> If you try to launch a CloudFormation stack that uses a Philter AMI but you do not have an active subscription to Philter via the AWS Marketplace the stack creation will fail. To remedy this, [go the AWS Marketplace and subscribe to Philter](https://aws.amazon.com/marketplace/pp/B07YVB8FFT).

