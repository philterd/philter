# Splitting Input Text

On a per-policy basis, Philter can split input text to process each split individually. This can improve performance and allows for handling long input text. Splitting is disabled by default.

An example split configuration in a policy is shown below

```
{
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    }
  },
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

The method of splitting the text will be the `newline` method. This method will cause Philter to split the text based on the locations of new line characters in the input text. The `width` method wraps the text at word boundaries into pieces of at most `threshold` characters, and the `characters` method splits the text into pieces of `threshold` characters. An unrecognized method falls back to `newline`.

Because the newline method splits text based on the locations of new line characters in the text, the text contained in the reassembled filter responses may not be an exact match of the input text. This is due to white space and other characters that may reside near the new line characters that get omitted during processing.

### Text Splitting Policy Properties

| Property    | Description                                                                                                                                                                                | Allowed Values     | Default Value |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------ | ------------- |
| `enabled`   | Whether or not input texts are split. Set to `false` to disable splitting; the `threshold` is then not checked. | `true` or `false`  | `false`       |
| `threshold` | Input text whose length is greater than or equal to this number of characters is split. For the `width` and `characters` methods, also the maximum piece length. | Any integer value. | `10000`       |
| `method`    | How to split the text.                                                                                                                                                                     | `newline`, `width`, `characters` | `newline`     |
| `overlap`   | Number of characters each piece shares with the end of the previous piece, so a value that crosses a piece boundary can be detected whole. Spans found in the overlap are de-duplicated. | `0` or greater | `0` |

### Alternative to Philter Splitting Text

In some cases it may be best to split your input text client side prior to sending the text to Philter. This gives you full control over how the text will be split and provides more predictable responses from Philter because you know how the text is split.

Choose boundaries that preserve complete records, identifiers, and UTF-8 characters. Arbitrary byte splitting can divide a sensitive value so that neither request detects it. The following example assumes each complete record occupies one line and is short enough for the request limit. It splits by lines and processes every generated segment in order.

Set `API_KEY` to a key with the `redact` scope. Run this Bash example from the directory containing `largefile.txt`:

```bash
set -euo pipefail
segment_dir=$(mktemp -d)
trap 'rm -rf "$segment_dir"' EXIT
split -l 100 largefile.txt "$segment_dir/segment-"

for segment in "$segment_dir"/segment-*; do
  [ -f "$segment" ] || continue
  curl --fail-with-body -sS -k -X POST "https://localhost:8080/api/filter" \
    -H "Authorization: Bearer $API_KEY" -H "Content-Type: text/plain" \
    --data-binary "@$segment" > "$segment.redacted"
done

# Publish the combined output only after every request has succeeded.
: > "$segment_dir/combined"
for result in "$segment_dir"/segment-*.redacted; do
  [ -f "$result" ] || continue
  cat "$result" >> "$segment_dir/combined"
done
cp "$segment_dir/combined" filtered.txt
```

Each request gets its own server-generated document ID. To reuse context-scoped replacements across chunks, create a context first and pass its name with `c`; no `d` parameter is supported. Splitting changes the surrounding text available to detection, so compare chunked results with representative complete inputs. Replace the host as needed and omit `-k` for a trusted certificate.
