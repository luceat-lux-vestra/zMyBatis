#!/usr/bin/env python3
"""Deterministic positive/negative controls for live-settings drift detection."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

POLICY_DIR = Path(__file__).resolve().parent
REPO_ROOT = POLICY_DIR.parents[1]
sys.path.insert(0, str(POLICY_DIR))

import check_live_settings as live  # noqa: E402

POLICY_PATH = REPO_ROOT / ".github" / "merge-gate-policy.yml"
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "repository-settings-drift.yml"
POLICY_TEXT = POLICY_PATH.read_text(encoding="utf-8")
POLICY = live.parse_policy(POLICY_TEXT)

GOOD_REPOSITORY = {
    "visibility": "public",
    "archived": False,
    "default_branch": "main",
    "allow_squash_merge": True,
    "allow_merge_commit": False,
    "allow_rebase_merge": False,
    "allow_auto_merge": False,
    "delete_branch_on_merge": True,
    "allow_update_branch": True,
}

GOOD_RULESET = {
    "id": 22024054,
    "name": "main protection",
    "target": "branch",
    "source_type": "Repository",
    "source": "luceat-lux-vestra/zMyBatis",
    "enforcement": "active",
    "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
    "bypass_actors": [],
    "rules": [
        {"type": "deletion"},
        {"type": "non_fast_forward"},
        {"type": "required_linear_history"},
        {
            "type": "pull_request",
            "parameters": {
                "required_approving_review_count": 0,
                "dismiss_stale_reviews_on_push": True,
                "required_reviewers": [],
                "require_code_owner_review": False,
                "require_last_push_approval": False,
                "required_review_thread_resolution": True,
                "require_extra_approval_for_unattributed_changes": True,
                "allowed_merge_methods": ["squash"],
            },
        },
        {
            "type": "required_status_checks",
            "parameters": {
                "strict_required_status_checks_policy": True,
                "do_not_enforce_on_create": False,
                "required_status_checks": [
                    {"context": "Build", "integration_id": 15368},
                    {"context": "Test", "integration_id": 15368},
                    {"context": "Inspect code", "integration_id": 15368},
                    {"context": "Verify plugin", "integration_id": 15368},
                    {"context": "Lint workflows", "integration_id": 15368},
                ],
            },
        },
    ],
}


class LiveSettingsPolicyTest(unittest.TestCase):
    def failures(self, repo=None, ruleset=None):
        return live.compare_live(
            POLICY,
            POLICY_TEXT,
            GOOD_REPOSITORY if repo is None else repo,
            GOOD_RULESET if ruleset is None else ruleset,
        )

    def test_known_good_live_state_is_accepted(self):
        self.assertEqual([], self.failures())

    def test_repository_merge_method_drift_is_rejected(self):
        repo = copy.deepcopy(GOOD_REPOSITORY)
        repo["allow_merge_commit"] = True
        self.assertTrue(self.failures(repo=repo))

    def test_hidden_bypass_actors_is_insufficient_evidence(self):
        ruleset = copy.deepcopy(GOOD_RULESET)
        del ruleset["bypass_actors"]
        failures = self.failures(ruleset=ruleset)
        self.assertTrue(any("insufficient evidence" in item for item in failures))

    def test_nonempty_bypass_actors_is_rejected(self):
        ruleset = copy.deepcopy(GOOD_RULESET)
        ruleset["bypass_actors"] = [
            {"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}
        ]
        self.assertTrue(self.failures(ruleset=ruleset))

    def test_required_context_drift_is_rejected(self):
        ruleset = copy.deepcopy(GOOD_RULESET)
        ruleset["rules"][-1]["parameters"]["required_status_checks"].pop()
        self.assertTrue(self.failures(ruleset=ruleset))

    def test_required_context_integration_drift_is_rejected(self):
        ruleset = copy.deepcopy(GOOD_RULESET)
        ruleset["rules"][-1]["parameters"]["required_status_checks"][0]["integration_id"] = 999
        self.assertTrue(self.failures(ruleset=ruleset))

    def test_extra_ruleset_rule_is_rejected(self):
        ruleset = copy.deepcopy(GOOD_RULESET)
        ruleset["rules"].append({"type": "required_signatures"})
        self.assertTrue(self.failures(ruleset=ruleset))

    def test_checked_in_workflow_contract_is_accepted(self):
        text = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertEqual([], live.check_workflow(POLICY, text))

    def test_schedule_weakening_is_rejected(self):
        text = WORKFLOW_PATH.read_text(encoding="utf-8").replace("23 2 * * *", "23 2 * * 1", 1)
        self.assertTrue(live.check_workflow(POLICY, text))

    def test_if_guard_disabling_audit_is_rejected(self):
        text = WORKFLOW_PATH.read_text(encoding="utf-8").replace(
            "    name: Audit repository settings\n",
            "    name: Audit repository settings\n    if: false\n",
            1,
        )
        self.assertTrue(live.check_workflow(POLICY, text))

    def test_checker_name_in_comment_does_not_count_as_execution(self):
        text = WORKFLOW_PATH.read_text(encoding="utf-8").replace(
            "        run: python3 .github/workflow-policy/check_live_settings.py .github/merge-gate-policy.yml",
            "        # python3 .github/workflow-policy/check_live_settings.py .github/merge-gate-policy.yml\n"
            "        run: echo skipped",
            1,
        )
        self.assertTrue(live.check_workflow(POLICY, text))


if __name__ == "__main__":
    unittest.main()
