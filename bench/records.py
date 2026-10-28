"""The company records and the delegation scope that a guard run starts with (C2 and C3 of plan 13.11).

Records come only from the clean initial environment of a suite, loaded with no injections, and from the frozen tool
classification (declarations/). No injection task, attack text or user-task ground truth is read here: those would let
the defence know the answer. What a company really has in its systems before an agent starts, and nothing more:

    known_counterparties   payees the account has paid or scheduled before, and its own account number
    known_contacts         people in the address book and the mailbox of the account
    known_files            file names and ids of the drive or the bank's file store
    known_channels         chat channels and the users of the chat workspace
    known_urls             web pages the workspace knows
    known_places           hotels, restaurants and rental companies of the travel catalogue
    known_events           calendar events of the account

    python records.py --suite banking
"""
from __future__ import annotations

import argparse
import json
import re
from functools import lru_cache
from pathlib import Path

from agentdojo.task_suite.load_suites import get_suite

DECLARATIONS = Path("E:/projects/contexa-benchmark/declarations")
BENCHMARK_VERSION = "v1.2.2"

# Operations that the delegation record allows an assistant to perform on its own; the others (a credential change and
# an administrative change of membership or access) need the delegator's confirmation, as an access policy would say.
ALLOWED_OPERATIONS = ["READ", "WRITE", "DELETE", "SEND", "SHARE", "TRANSFER", "RESERVE", "EXPORT"]
CONFIRMATION_OPERATIONS = ["CREDENTIAL", "ADMIN", "EXECUTE"]


def load_declarations(name: str = "agentdojo-tools.yml") -> dict[str, dict[str, str]]:
    text = (DECLARATIONS / name).read_text(encoding="utf-8")
    tools: dict[str, dict[str, str]] = {}
    for match in re.finditer(r"^  ([A-Za-z0-9_]+):\s*\{([^}]*)\}", text, flags=re.M):
        fields = {k.strip(): v.strip() for k, v in (part.split(":", 1) for part in match.group(2).split(","))}
        tools[match.group(1)] = fields
    return tools


def _emails(items) -> list[str]:
    return sorted({str(item["email"]).lower() for item in items if item.get("email")})


@lru_cache(maxsize=None)
def records_for(suite_name: str) -> dict[str, list[str]]:
    suite = get_suite(BENCHMARK_VERSION, suite_name)
    env = suite.load_and_inject_default_environment({}).model_dump()
    records: dict[str, list[str]] = {}
    if suite_name == "banking":
        account = env["bank_account"]
        payees = {t["recipient"] for t in account["transactions"] + account["scheduled_transactions"]}
        payees |= {t["sender"] for t in account["transactions"] + account["scheduled_transactions"]}
        payees.discard("me")
        payees.add(account["iban"])
        records["known_counterparties"] = sorted(payees)
        records["known_files"] = sorted(env["filesystem"]["files"])
    elif suite_name == "slack":
        slack = env["slack"]
        records["known_users"] = sorted(slack["users"])
        records["known_channels"] = sorted(slack["channels"])
        records["known_urls"] = sorted(env["web"]["web_content"])
    elif suite_name == "travel":
        records["known_contacts"] = _emails(env["inbox"]["contact_list"]) + [env["user"]["email"].lower()]
        places = [h["name"] for h in env["hotels"]["hotel_list"]]
        places += [r["name"] for r in env["restaurants"]["restaurant_list"]]
        places += [c["name"] for c in env["car_rental"]["company_list"]]
        records["known_places"] = sorted(set(places))
        records["known_events"] = sorted(str(e["title"]) for e in env["calendar"]["initial_events"])
    elif suite_name == "workspace":
        records["known_contacts"] = _emails(env["inbox"]["contact_list"]) + [env["inbox"]["account_email"].lower()]
        files = env["cloud_drive"]["files"].values()
        records["known_files"] = sorted({str(f["filename"]) for f in files} | {str(f["id_"]) for f in files})
        records["known_events"] = sorted(str(e["title"]) for e in env["calendar"]["initial_events"])
    else:
        raise ValueError(f"unknown suite {suite_name}")
    return records


@lru_cache(maxsize=None)
def owner_context(suite_name: str) -> dict[str, list[str]]:
    """What the owner knows when asked to confirm: the names of the guard's records, and, for the bank, the text of the
    owner's own files (a bill, a notice of the landlord), which an owner has read. All of it is from the clean initial
    environment; nothing of an attack."""
    known = dict(records_for(suite_name))
    if suite_name == "banking":
        env = get_suite(BENCHMARK_VERSION, suite_name).load_and_inject_default_environment({}).model_dump()
        known["your_own_files_text"] = [" ".join(f"{name}: {str(text)[:500]}".split())
                                        for name, text in env["filesystem"]["files"].items()]
    return known


def scope_for(suite_name: str, declarations: dict[str, dict[str, str]]) -> dict:
    suite = get_suite(BENCHMARK_VERSION, suite_name)
    categories = sorted({declarations[tool.name]["category"] for tool in suite.tools})
    return {
        "family": {"banking": "PERSONAL_BANKING", "slack": "TEAM_COLLABORATION", "travel": "TRAVEL_ASSISTANCE",
                   "workspace": "OFFICE_ASSISTANCE"}[suite_name],
        "allowedOperations": ALLOWED_OPERATIONS,
        "confirmationOperations": CONFIRMATION_OPERATIONS,
        "allowedResources": categories,
    }


def injecagent_scope(declarations: dict[str, dict[str, str]]) -> dict:
    """InjecAgent has no environment and no company records; the scope is the same for every case (it is not derived
    from the case's user tool, which would hand the defence the legitimate action)."""
    return {
        "family": "GENERAL_ASSISTANCE",
        "allowedOperations": ALLOWED_OPERATIONS,
        "confirmationOperations": CONFIRMATION_OPERATIONS,
        "allowedResources": sorted({d["category"] for d in declarations.values()}),
    }


def begin_payload_extras(suite_name: str) -> dict:
    return {"records": records_for(suite_name), "scope": scope_for(suite_name, load_declarations())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    args = parser.parse_args()
    payload = begin_payload_extras(args.suite)
    print(json.dumps({k: (v if k == "scope" else {n: len(x) for n, x in v.items()}) for k, v in payload.items()},
                     indent=2, ensure_ascii=False))
