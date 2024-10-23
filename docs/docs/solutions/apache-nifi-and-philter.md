# Apache NiFi and Philter

This article describes how [Philter](https://www.philterd.ai/philter/) can be used with Apache NiFi to filter sensitive information such as PII and PHI within an Apache NiFi data flow.

Philter is available on the [AWS, Azure, and Google Cloud marketplaces](https://www.philterd.ai/philter/availability/). So, fire up an instance of Philter and let's get started using it alongside your Apache NiFi data flow!

### Configuring Philter with Cloudera DataFlow (CDF)

Philter is certified to work with [Cloudera DataFlow](https://www.cloudera.com/products/cdf.html) (CDF) as a custom Apache NiFi processor. There are two options for deploying Philter with CDF.

#### Option 1 - Using Philter via its API

In the first option, a custom NiFi processor performs redaction by communicating with an instance of Philter through Philter's API. The processor sends text to Philter for redaction and receives back the redacted text. This option requires deploying an instance of Philter alongside your Cloudera DataFlow installation. Next, get the Philter NiFi processor from [GitHub](https://github.com/mtnfog/philter-nifi). Deploy the NAR file to CDF and make it accessible to Apache NiFi.

Configure the Philter processor by specifying the location of Philter and any other necessary connection configuration, as shown in the image below.

For a production environment, a cluster of Philter instances deployed behind a load balancer would provide improved performance and increased availability over a single instance.

#### Option 2 - Using Philter Embedded into NiFi

The second option does not require an instance of Philter. Please contact us to receive a NiFi processor with all of Philter's capabilities embedded in it. This processor performs the text redaction entirely within your NiFi data flow with no external communication required. This processor is significantly more performant than the processor in the first option. When you receive the processor NAR file from us, deploy it to NiFi.

Configure the processor as shown in the image below by specifying the name of the desired policy and filtering context:

### Creating a Flow

Both processors support the same transitions. The `redacted` transition contains the redacted version of the flow file's content. In the example flows shown below, the top flow uses the Philter processor utilizing Philter's API. The bottom flow uses the Philter embedded processor. As you can see, both flows are the same. The only differences are the middle processors and their individual configuration.
