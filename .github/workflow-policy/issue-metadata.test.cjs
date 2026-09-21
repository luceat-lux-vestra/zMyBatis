"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { compilePolicy, expectedType, reconcileIssue } = require("./issue-metadata.cjs");

const policy = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "issue-metadata-policy.json"), "utf8"));
const { rules } = compilePolicy(policy);

test("canonical prefixes map deterministically", () => {
  const cases = new Map([
    ["bug(editor): stale SQL", "type:bug"],
    ["feat(core): add capability", "type:feature"],
    ["security: harden execution", "type:security"],
    ["docs: update contract", "type:docs"],
    ["design(evaluation): freeze boundary", "type:research"],
    ["hardening(reassessment): refresh controls", "type:task"],
    ["governance: classify failure signals", "type:task"],
    ["Investigate mapper behavior", null]
  ]);
  for (const [title, expected] of cases) assert.equal(expectedType(title, rules), expected, title);
});

test("explicit title is authoritative for the managed type only", () => {
  assert.deepEqual(reconcileIssue({
    title: "docs: update contract",
    labels: ["type:task", "area:evaluation"]
  }, policy), {
    expectedType: "type:docs",
    add: ["type:docs"],
    remove: ["type:task"],
    diagnostics: []
  });
});

test("unknown title preserves a single maintainer-selected type", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate mapper behavior",
    labels: ["type:research", "area:evaluation"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: []
  });
});

test("unknown untyped title is diagnostic only", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate mapper behavior",
    labels: ["area:evaluation"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: ["unclassified-title"]
  });
});

test("ambiguous managed types without explicit authority fail closed", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate mapper behavior",
    labels: ["type:task", "type:research", "area:evaluation"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: ["ambiguous-managed-type"]
  });
});

test("explicit authority repairs ambiguous managed types without touching unrelated labels", () => {
  assert.deepEqual(reconcileIssue({
    title: "bug: stale result",
    labels: ["type:task", "type:research", "area:evaluation"]
  }, policy), {
    expectedType: "type:bug",
    add: ["type:bug"],
    remove: ["type:research", "type:task"],
    diagnostics: []
  });
});
