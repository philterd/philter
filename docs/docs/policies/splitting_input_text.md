# Splitting Input Text

On a per-policy basis, Philter can split input text to process each split individually. This can improve performance and allows for handling long input text. Splitting is disabled by default.

An example split configuration in a policy is shown below

```
{
  "name": "default",
  "identifiers": {}, 
  "config": {
    "splitting": {
      "enabled": true,
      "threshold": 10000,
      "method": "newline"
    }
  }
}
```

In this example policy, splitting is enabled for inputs greater than equal to 10,000 characters in length.

The method of splitting the text will be the `newline` method. This method will cause Philter to split the text based on the locations of new line characters in the input text. Additional methods of text splitting may be added in future versions.

Because the newline method splits text based on the locations of new line characters in the text, the text contained in the reassembled filter responses may not be an exact match of the input text. This is due to white space and other characters that may reside near the new line characters that get omitted during processing.

### Text Splitting Policy Properties

| Property    | Description                                                                                                                                                                                | Allowed Values     | Default Value |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------ | ------------- |
| `enabled`   | Whether or not input texts are split. Whether or not input texts are split. When `false`, requests with text exceeding the threshold generate a `HTTP 413 PayloadTooLarge` error response. | `true` or `false`  | `false`       |
| `threshold` | When to split the input text. Set to `-1` to disable splitting.                                                                                                                            | Any integer value. | `10000`       |
| `method`    | How to split the text.                                                                                                                                                                     | `newline`          | `newline`     |

### Alternative to Philter Splitting Text

In some cases it may be best to split your input text client side prior to sending the text to Philter. This gives you full control over how the text will be split and provides more predictable responses from Philter because you know how the text is split.

An example of splitting text into chunks prior to sending the text to Philter is given in the commands below:

```
# Given a large file called largefile.txt, split it into 10k pieces.
$ split -b 10k largefile.txt segment

# Now process the pieces.
$ curl -s -X POST -k "https://philter:8080/api/filter?d=document1" --data "@/tmp/segmentaa" -H "Content-type: text/plain" > out1
$ curl -s -X POST -k "https://philter:8080/api/filter?d=document1" --data "@/tmp/segmentab" -H "Content-type: text/plain" > out2

# Now recombine the outputs into a single file.
$ cat out1 out2 > filtered.txt
```
