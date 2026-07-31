# Model manifests

One JSON file per downloadable model. `ModelManifest.parse` reads them and **refuses any
whose digests are not filled in**, so a build cannot ship a manifest that would download
several gigabytes and verify nothing.

## Filling one in

The `sha256`, `bytes`, and `url` fields describe an artifact that exists. Until the
artifact is chosen and published, they are placeholders and the manifest will not parse —
that is the intended behaviour, not a bug to work around. To complete one:

```sh
# for each file the model is made of
curl -L -o weights.gguf "<url>"
sha256sum weights.gguf     # -> sha256
stat -c %s weights.gguf    # -> bytes
```

Paste the values in, and `ModelManifestTest` will start accepting the file.

Do **not** relax the check to get a build green. An unverified download is the one thing
the mechanism exists to prevent: transport security establishes who sent the bytes, and
the hash establishes that they are the bytes this build was tested against. Only the
second survives a compromised mirror, a captive-portal page saved under the model's name,
or a truncation nobody noticed.

## Which model

The generative model is a build-time choice constrained by size, licence, and quality;
nothing in `03-model-runtime-spec.md` depends on the identity. `gemma-e2b.json` and
`gemma-e4b.json` are shaped for the Gemma E2B / E4B pair — the effective-2B and
effective-4B variants — because the smaller one is the realistic floor for a phone and the
larger is what a recent device can hold. Pick one per release, or ship both and let the
device decide.

Their `url` fields carry the publisher's placeholder rather than a guessed link. Point
them at whatever release you actually download and verified.

## What is not here

The embedder and the ASR model ship **inside the app binary**, not through this
directory. That is the whole point of the split: retrieval works before the big download
finishes, so a user on a metered connection at a game table gets a working rules
reference immediately.
