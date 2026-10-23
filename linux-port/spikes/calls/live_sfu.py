#!/usr/bin/env python3
"""Opt-in Cloudflare SFU control-plane smoke; never claims media was established.

No credentials, session identifiers, provider bodies or URLs are printed. This uses
an ephemeral unconnected session (provider expires it), not account configuration.
Full publish/subscribe, billing and frame-cryptor interop remain separate gates.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

MAX_RESPONSE = 65536


def configuration(env):
    if env.get("RELAY_SPIKE_LIVE") != "1":
        raise ValueError("Live SFU requires RELAY_SPIKE_LIVE=1")
    app_id = env.get("RELAY_SFU_APP_ID", "")
    secret = env.get("RELAY_SFU_APP_SECRET", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", app_id):
        raise ValueError("Missing or invalid RELAY_SFU_APP_ID")
    if not secret or "\r" in secret or "\n" in secret:
        raise ValueError("Missing or invalid RELAY_SFU_APP_SECRET")
    return app_id, secret


def smoke(env):
    app_id, secret = configuration(env)  # Guard before constructing any request.
    base = "https://rtc.live.cloudflare.com/v1/apps/" + app_id
    request = urllib.request.Request(
        base + "/sessions/new", data=b"{}", method="POST",
        headers={"Authorization": "Bearer " + secret, "Content-Type": "application/json"},
    )
    # Do not follow redirects that could disclose Authorization to another origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(request, timeout=20) as response:
        if response.status != 200:
            raise ValueError("Unexpected provider status")
        raw = response.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE:
            raise ValueError("Oversized provider response")
        result = json.loads(raw)
        session_id = result.get("sessionId", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", session_id):
            raise ValueError("Missing or invalid provider session id")
    # Read exact newly-created resource back; creation alone is not verification.
    check = urllib.request.Request(
        base + "/sessions/" + session_id, method="GET",
        headers={"Authorization": "Bearer " + secret, "Content-Type": "application/json"},
    )
    with opener.open(check, timeout=20) as response:
        if response.status != 200:
            raise ValueError("Session readback failed")
        raw = response.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE:
            raise ValueError("Oversized readback")
        json.loads(raw)
    print("sfu_session_create_readback=PASS media_publish_subscribe=NOT_RUN egress=NOT_MEASURED")


def main():
    try:
        smoke(os.environ)
    except ValueError as error:
        # Only our fixed messages, never a raw provider/decode error with body data.
        print("sfu_smoke=BLOCKED_OR_FAILED type=" + type(error).__name__, file=sys.stderr)
        return 2
    except (urllib.error.URLError, TimeoutError, OSError, KeyError, TypeError):
        print("sfu_smoke=FAILED provider_detail=SUPPRESSED", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
