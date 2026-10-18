# Test models

`PHEYE_MODELS_DIRECTORY` for the integration tests. `test-fixture` is a synthetic GLiNER model, not a
trained one: `model.onnx` is the graph from phileas-pheye-onnx's `src/test/resources/gliner-fixture`,
which scores the span of the first two words of any input as the first label and nothing else, and
`tokenizer.json` is a minimal word-level tokenizer that maps every word to `[UNK]` except the
`<<ENT>>` and `<<SEP>>` prompt tokens the graph counts.
