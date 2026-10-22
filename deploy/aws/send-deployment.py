"""GitHub runner helper: send one SSM command, then wait for its real result."""
import json
import os
import re
import shlex
import subprocess
import time


def aws(*args):
    return subprocess.run(
        ["aws", "ssm", *args, "--output", "json"],
        check=False, text=True, capture_output=True,
    )


backend = os.environ["BACKEND_IMAGE"]
frontend = os.environ["FRONTEND_IMAGE"]
instance = os.environ["INSTANCE_ID"]
for ref in (backend, frontend):
    if not re.fullmatch(r"ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}", ref):
        raise SystemExit("Invalid image digest reference")
if not re.fullmatch(r"i-[a-f0-9]+", instance):
    raise SystemExit("Invalid instance ID")

command = shlex.join(["/opt/betterf/deploy.sh", backend, frontend])
result = aws(
    "send-command", "--instance-ids", instance,
    "--document-name", "AWS-RunShellScript",
    "--timeout-seconds", "600",
    "--parameters", json.dumps({"commands": [command], "executionTimeout": ["900"]}),
)
if result.returncode:
    raise SystemExit(result.stderr)
command_id = json.loads(result.stdout)["Command"]["CommandId"]
print(f"SSM command ID: {command_id}", flush=True)

deadline = time.monotonic() + 1200
while time.monotonic() < deadline:
    result = aws("get-command-invocation", "--command-id", command_id,
                 "--instance-id", instance)
    if result.returncode:
        if "InvocationDoesNotExist" in result.stderr:
            time.sleep(5)  # SSM results are eventually consistent.
            continue
        raise SystemExit(result.stderr)
    invocation = json.loads(result.stdout)
    status = invocation["Status"]
    if status in {"Pending", "InProgress", "Delayed", "Cancelling"}:
        time.sleep(10)
        continue
    print(invocation.get("StandardOutputContent", ""))
    print(invocation.get("StandardErrorContent", ""))
    print(f"SSM status: {status}")
    raise SystemExit(0 if status == "Success" else 1)
raise SystemExit("Timed out waiting for SSM. Check the command in AWS before retrying.")
