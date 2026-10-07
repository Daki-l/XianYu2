# Release Metadata

Every formal `vX.Y.Z` tag must include `release-metadata/vX.Y.Z.json` in the tagged commit. The Release workflow rejects a tag without this file, a tag mismatch, or an invalid schema.

Routine releases use this form:

```json
{
  "releaseTag": "vX.Y.Z",
  "hostContractRequired": false,
  "minimumAgentVersion": 1
}
```

Set `hostContractRequired` to `true` when the release changes Compose, the update agent, systemd, directory permissions, Docker requirements, or another host contract. Such a release must explain the manual work and can only be applied by a host administrator after that work is complete:

```json
{
  "releaseTag": "vX.Y.Z",
  "hostContractRequired": true,
  "minimumAgentVersion": 2,
  "manual": {
    "reason": "The update agent and its systemd permissions changed.",
    "instructions": "Verify and install the host package from this Release, then run the documented host migration."
  }
}
```

`minimumAgentVersion` cannot exceed `deploy/update/agent-version` packaged by the same tag. Any agent version increase must be delivered through the signed host package; do not use `workflow_dispatch` inputs to reclassify an existing tag.
