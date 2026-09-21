"use strict";

function compilePolicy(policy) {
  const managed = new Set(policy.labels.map((entry) => entry.name));
  const rules = policy.rules.map(({ pattern, label }) => [new RegExp(pattern, "i"), label]);
  return { managed, rules };
}

function expectedType(title, rules) {
  const value = String(title || "").trim();
  for (const [pattern, label] of rules) {
    if (pattern.test(value)) return label;
  }
  return null;
}

function reconcileIssue(issue, policy) {
  const { managed, rules } = compilePolicy(policy);
  const labels = (issue.labels || [])
    .map((label) => typeof label === "string" ? label : label?.name)
    .filter(Boolean);
  const existing = labels.filter((label) => managed.has(label));
  const expected = expectedType(issue.title, rules);
  const add = [];
  const remove = [];
  const diagnostics = [];

  if (!expected) {
    if (existing.length === 0) diagnostics.push("unclassified-title");
    if (existing.length > 1) diagnostics.push("ambiguous-managed-type");
    return { expectedType: null, add, remove, diagnostics };
  }

  if (!labels.includes(expected)) add.push(expected);
  for (const label of existing) {
    if (label !== expected) remove.push(label);
  }

  return {
    expectedType: expected,
    add: add.sort(),
    remove: remove.sort(),
    diagnostics
  };
}

module.exports = { compilePolicy, expectedType, reconcileIssue };
