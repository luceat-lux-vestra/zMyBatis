# Marketplace Publication Recovery

This runbook is the maintainer-owned recovery path when a zMyBatis release stops after `zmybatis-release-identity.json` is uploaded but before `zmybatis-release-published.json` is established.

A failed workflow is not evidence that Marketplace publication failed. Never blindly rerun while the pending identity exists.

## Immutable identity

Recovery is anchored by all of:

- protected release tag `vMAJOR.MINOR.PATCH[-PRERELEASE]`;
- exact tag commit reachable from reviewed `main`;
- effective plugin version (tag without the leading `v`);
- signed artifact filename;
- signed artifact SHA-256;
- Marketplace channel derived from the effective version;
- GitHub Release owning the pending/completed identity.

Never repoint or recreate the tag, and never reuse a version for different source or different signed bytes.

## 1. Freeze and inspect

Record the release URL, workflow run/failed step, tag, tag commit, pending/completed identity asset IDs, signed ZIP asset ID if present, and authoritative Marketplace state.

Download GitHub-side identity without modifying it:

```bash
repo="luceat-lux-vestra/zMyBatis"
tag="v27.0.0"

gh api "repos/$repo/releases/tags/$tag" > /tmp/zmybatis-release.json

pending_id="$(jq -r '.assets[]? | select(.name == "zmybatis-release-identity.json") | .id' /tmp/zmybatis-release.json)"
published_id="$(jq -r '.assets[]? | select(.name == "zmybatis-release-published.json") | .id' /tmp/zmybatis-release.json)"

[ -n "$pending_id" ] && gh api -H 'Accept: application/octet-stream'   "repos/$repo/releases/assets/$pending_id" > /tmp/zmybatis-release-identity.json
[ -n "$published_id" ] && gh api -H 'Accept: application/octet-stream'   "repos/$repo/releases/assets/$published_id" > /tmp/zmybatis-release-published.json
```

Require one unambiguous identity. Duplicate/conflicting pending or completed markers are a hard stop.

## 2. Verify source and artifact identity

For a pending identity require:

```bash
jq -e '
  .schema == 1 and
  .publication_state == "pending" and
  (.tag | test("^v")) and
  (.version | type == "string") and
  (.commit | test("^[0-9a-f]{40}$")) and
  (.artifact | endswith("-signed.zip")) and
  (.artifact_sha256 | test("^[0-9a-f]{64}$")) and
  (.marketplace_channel | type == "string") and
  .author_signed == true
' /tmp/zmybatis-release-identity.json
```

The tag must still resolve to the recorded commit and that commit must remain reachable from reviewed `main`.

If the signed GitHub Release ZIP exists, download it and require its filename and SHA-256 to match the pending identity exactly. A mismatch is a hard stop; do not overwrite it.

## 3. Classify Marketplace state

Use JetBrains Marketplace publisher UI or an official Marketplace read/API surface to classify the exact plugin/version as exactly one of:

- **Exists** — the exact version was accepted/present, including processing/review state;
- **Absent** — authoritative evidence says the exact version does not exist;
- **Unknown** — unavailable, ambiguous, delayed, or insufficient evidence.

`Unknown` fails closed. Public visibility lag is not proof of absence.

## 4. Recovery decision

| Marketplace state | GitHub state | Allowed action |
| --- | --- | --- |
| Exists | pending identity + matching signed asset | do not publish again; create the completed marker after explicit maintainer authorization |
| Exists | pending identity but signed asset missing | recover only if the exact signed bytes identified by the pending SHA can be obtained/reproduced and verified; otherwise stop and use a new version |
| Absent | pending identity + exact signed bytes available | maintainer may explicitly authorize one recovery publication attempt of that exact identity |
| Absent | exact signed bytes unavailable | stop; use a new version |
| Unknown | any | stop; no retry |
| Any | identity/tag/version/digest conflict | stop; investigate |

A recovery publication is never inferred from workflow failure. It requires explicit maintainer authorization after Marketplace state is proven `Absent`.

## 5. Completing an already-published release

When Marketplace state is `Exists`:

1. do not call `publishPlugin` again;
2. require the GitHub Release signed ZIP to match the pending artifact name and SHA-256;
3. create `zmybatis-release-published.json` by changing only `publication_state` from `pending` to `published`;
4. verify every other identity field is unchanged;
5. upload the completed marker only after explicit maintainer authorization.

After that, a normal workflow replay verifies the completed identity and signed release asset and returns `publish=false`.

## 6. Withdrawal and incorrect releases

Marketplace withdrawal/hiding, GitHub Release reconciliation, source rollback, and a corrected publication are separate decisions. None authorizes tag rewrite or version reuse. An incorrect published artifact is corrected with a new version and a new immutable identity.

## Hard-stop conditions

Stop without publication or identity mutation if:

- Marketplace state is `Unknown`;
- tag commit or reviewed-main ancestry differs;
- effective plugin version differs;
- signed artifact filename or SHA-256 differs;
- pending/completed marker fields conflict;
- duplicate identity assets exist;
- exact signed bytes required for recovery are unavailable;
- recovery would require changing the protected tag or reusing the version for different source/artifact.
